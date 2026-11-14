package es.jllopez.neis.application;

import es.jllopez.neis.domain.model.Address;
import es.jllopez.neis.domain.model.AddressCapability;
import es.jllopez.neis.domain.model.FilterSpec;
import es.jllopez.neis.domain.model.NewsEventItem;
import es.jllopez.neis.domain.model.PaginationInfo;
import es.jllopez.neis.domain.model.ScrapeResult;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Executes one TASK TYPE over one address (US-05, epic #5): the
 * runnable units behind the executor. The step codes are the SSAFP
 * pipeline's stages, so running a step means performing the
 * pipeline UP TO AND INCLUDING that step — every stage consumes the
 * previous one's output:
 *
 * <pre>
 * WEBSCRAPE -> SIMPLIFY -> ANALYZE -> FILTER -> PRINT
 * CHAIN = PRINT (the whole pipeline)
 * </pre>
 *
 * <p>Artifacts land in the same per-address work tree the pipeline
 * uses; the summary string becomes the execution record's outcome.
 * This service reaches the pipeline service's stage methods through
 * package-private access — same application layer, no new port: the
 * stages are application orchestration, not external adapters.</p>
 */
@Service
public class TaskWorkService {

    /** Safety guard for the pagination iteration (US-12.4). */
    private static final int MAX_PAGES = 10;

    private final PipelineService pipeline;
    private final AddressCatalogService addressCatalog;
    /** Externalized default cap (US-12.5): null = no limit. */
    private final Integer configuredMaxItems;

    public TaskWorkService(PipelineService pipeline, AddressCatalogService addressCatalog,
                           @org.springframework.beans.factory.annotation.Value(
                                   "${neis.extraction.max-items:}") String configuredMaxItems) {
        this.pipeline = pipeline;
        this.addressCatalog = addressCatalog;
        this.configuredMaxItems = parseMaxItems(configuredMaxItems);
    }

    /**
     * US-12.5: the externalized cap is optional — absent (blank) means
     * no limit; a present value must be a positive integer (a bad
     * configuration fails the boot instead of silently running
     * unlimited).
     */
    private static Integer parseMaxItems(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        int value;
        try {
            value = Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "neis.extraction.max-items must be an integer, got: " + raw);
        }
        if (value < 1) {
            throw new IllegalArgumentException(
                    "neis.extraction.max-items must be >= 1, got: " + value);
        }
        return value;
    }

    /**
     * Runs the task type over the address and returns the outcome
     * summary for the execution record.
     */
    public String execute(String taskCode, Address address) {
        return execute(taskCode, address, Map.of(), null);
    }

    /**
     * Runs the task type over the address with per-task parameters
     * (US-08.3, issue #35). PRINT and CHAIN accept {@code max-items}
     * (1..10000, the item cap; default 50) and {@code max-pages}
     * (1..100, the pagination-iteration guard; default 10); WEBSCRAPE
     * accepts {@code max-items} (US-12.5, issue #68: the extraction
     * cap — absent, the externalized {@code neis.extraction.max-items}
     * default applies; absent too, extraction is unlimited). Any other
     * parameter key is refused — SIMPLIFY, ANALYZE and FILTER take no
     * parameters (FILTER's chain-carried filter spec aside), and
     * passing one before then is an error, not silent dead
     * configuration.
     */
    public String execute(String taskCode, Address address,
                          Map<String, String> parameters) {
        return execute(taskCode, address, parameters, null);
    }

    /**
     * Same as {@link #execute(String, Address, Map)} with the name of
     * the chain whose step is being executed (null for single task
     * runs); the PRINT report artifact is titled with it.
     */
    public String execute(String taskCode, Address address,
                          Map<String, String> parameters, String chainName) {
        return execute(taskCode, address, parameters, chainName, null);
    }

    /**
     * Same as {@link #execute(String, Address, Map)} with the name of
     * the chain whose step is being executed (null for single task
     * runs; the PRINT report artifact is titled with it) and the
     * chain's filter spec (owner decision 2026-11-12): the criterion
     * the chain DEFINITION carries, applied offline by the FILTER
     * application of this run. Null when the chain defines no filter.
     */
    public String execute(String taskCode, Address address,
                          Map<String, String> parameters, String chainName,
                          FilterSpec chainFilterSpec) {
        return switch (taskCode) {
            case "WEBSCRAPE" -> {
                // US-12.5: the step accepts an optional max-items cap
                // (1..10000); absent, the externalized default applies;
                // absent too, extraction is unlimited.
                requireNoParameters(taskCode, parameters, "max-items");
                yield scrape(address, maxItemsCap(parameters));
            }
            case "SIMPLIFY" -> {
                requireNoParameters(taskCode, parameters);
                yield simplify(address);
            }
            case "ANALYZE" -> {
                requireNoParameters(taskCode, parameters);
                yield analyze(address);
            }
            case "FILTER" -> {
                // A chain's FILTER step carries the filter spec as its
                // parameters ("field"/"value"); the executor threads the
                // parsed spec to every filter-applying step of the run.
                FilterSpec spec = chainFilterSpec != null
                        ? chainFilterSpec : FilterSpec.of(parameters);
                requireNoParameters(taskCode, parameters,
                        FilterSpec.FIELD_KEY, FilterSpec.VALUE_KEY);
                yield filter(address, spec);
            }
            case "PRINT", "CHAIN" -> fullChain(address, parameters, chainName,
                    chainFilterSpec);
            default -> throw new IllegalArgumentException(
                    "Unknown task type: " + taskCode
                            + " (known: WEBSCRAPE, SIMPLIFY, ANALYZE, FILTER, PRINT, CHAIN)");
        };
    }

    private void requireNoParameters(String taskCode, Map<String, String> parameters,
                                     String... allowedKeys) {
        for (String key : parameters.keySet()) {
            boolean allowed = false;
            for (String allowedKey : allowedKeys) {
                allowed |= allowedKey.equals(key);
            }
            if (!allowed) {
                throw new IllegalArgumentException("Task type " + taskCode
                        + " takes no such parameter (got: " + key + "; allowed: "
                        + (allowedKeys.length == 0 ? "none" : String.join(", ", allowedKeys))
                        + ")");
            }
        }
    }

    /**
     * WEBSCRAPE (US-06 slice): obtain and store the listing HTML5; with
     * a preconfigured selector (US-12.1), also extract the news/events
     * list with metadata — deterministic element iteration, no AI
     * (US-06.4, issue #44) — iterating page by page when the listing is
     * paginated (US-12.4, issue #67): the next page is followed through
     * the page's own controls (rel=next or a pager link) while it
     * yields new items and the US-12.5 cap allows. The detection result
     * and the page count are recorded in the outcome. Without a
     * selector, extraction is skipped here: identifying a selector is
     * ANALYZE's job (AI or fallbacks).
     */
    private String scrape(Address address, int maxItems) {
        var scrape = pipeline.scrapePort.scrape(address);
        Path artifact = pipeline.writeArtifact(address, "WEBSCRAPE", "listing-html",
                "scrape.html", scrape.html());
        PaginationInfo pagination = pipeline.paginationDetector.detect(scrape.html());

        Optional<String> selector =
                pipeline.preconfiguredSelectors.find(address.id(), address.itemKind());
        if (selector.isEmpty()) {
            return "WEBSCRAPE: " + scrape.sizeBytes() + " bytes -> " + artifact
                    + "; no preconfigured selector for " + address.id()
                    + " — item extraction skipped (US-06.4); paginated: "
                    + pagination.paginated() + " (" + pagination.evidence() + ")";
        }

        List<NewsEventItem> items = new ArrayList<>(pipeline.extractionPort.extract(
                scrape.html(), selector.get(), address.itemKind(), address.id(),
                maxItems, address.url()));
        int pages = 1;
        List<String> notes = new ArrayList<>();
        java.util.Set<String> visited = new java.util.HashSet<>();
        String currentUrl = address.url().trim();
        visited.add(currentUrl);
        String nextUrl = items.size() < maxItems
                ? pipeline.extractionPort.nextPageUrl(scrape.html(), currentUrl)
                : null;
        boolean truncatedByGuard = false;
        while (nextUrl != null && items.size() < maxItems && pages < MAX_PAGES) {
            // The self-page check FIRST: the visited guard would
            // mislabel a self-referencing pager as a loop (review
            // finding, 2026-11-14).
            if (isCurrentPageAgain(nextUrl, currentUrl)) {
                // A next control pointing at the page itself is not
                // pagination: either the same URL or (1-indexed sites)
                // the same path with only page=1 added — the site's
                // static pager quirk observed live on europa.eu.
                notes.add("next control points at the current page");
                break;
            }
            if (!visited.add(nextUrl)) {
                notes.add("pagination loop at " + nextUrl);
                break;
            }
            ScrapeResult page;
            try {
                page = pipeline.scrapePort.scrapeUrl(nextUrl);
            } catch (UnsupportedOperationException e) {
                notes.add("page fetch unsupported: " + e.getMessage());
                break;
            } catch (RuntimeException e) {
                // Fetch errors (HTTP 429, timeout, malformed href) stop
                // the iteration but KEEP the pages already extracted —
                // the partial result is stored and the reason recorded,
                // never silently dropped (review finding, 2026-11-14).
                notes.add("page " + (pages + 1) + " fetch failed: "
                        + e.getMessage());
                break;
            }
            pages++;
            pipeline.writeArtifact(address, "WEBSCRAPE", "page-html",
                    "scrape-page" + pages + ".html", page.html());
            int before = items.size();
            for (NewsEventItem item : pipeline.extractionPort.extract(page.html(),
                    selector.get(), address.itemKind(), address.id(),
                    maxItems - items.size(), nextUrl)) {
                if (isNew(items, item)) {
                    items.add(item);
                }
            }
            if (items.size() == before) {
                notes.add("page " + pages + " yielded no new items");
                break;
            }
            nextUrl = items.size() < maxItems
                    ? pipeline.extractionPort.nextPageUrl(page.html(), nextUrl)
                    : null;
            if (nextUrl != null && pages >= MAX_PAGES - 1 && items.size() < maxItems) {
                // The loop condition is about to end on the page guard:
                // record the truncation instead of exiting silently
                // (review finding, 2026-11-14).
                truncatedByGuard = true;
            }
        }
        if (truncatedByGuard) {
            notes.add("iteration stopped at the " + MAX_PAGES + "-page safety guard");
        }

        Path itemsArtifact = pipeline.writeArtifact(address, "WEBSCRAPE",
                "items-json", "items.json", itemsJson(items));
        // 'cap M' states that extraction stopped at the cap — the count
        // is the recorded truth; whether MORE elements existed beyond the
        // cap is not observable through the extraction contract (review
        // finding 2026-11-14: 'reached' implied truncation, which a
        // listing of exactly maxItems items cannot prove).
        String capNote = maxItems == Integer.MAX_VALUE ? "no cap"
                : "cap " + maxItems;
        String iterationNote = notes.isEmpty() ? "" : "; " + String.join("; ", notes);
        return "WEBSCRAPE: " + scrape.sizeBytes() + " bytes -> " + artifact
                + "; items extracted: " + items.size() + " (" + capNote
                + "; selector '" + selector.get() + "') -> " + itemsArtifact
                + "; pages: " + pages + "; paginated: " + pagination.paginated()
                + " (" + pagination.evidence() + ")" + iterationNote;
    }

    /**
     * Duplicate check across pages: title + detail URL, case-sensitive
     * (case-insensitive matching merged genuinely distinct items —
     * review finding, 2026-11-14).
     */
    private static boolean isNew(List<NewsEventItem> items, NewsEventItem candidate) {
        String key = (candidate.title() == null ? "" : candidate.title().trim())
                + "|" + (candidate.url() == null ? "" : candidate.url().trim());
        return items.stream().noneMatch(existing ->
                ((existing.title() == null ? "" : existing.title().trim())
                        + "|" + (existing.url() == null ? "" : existing.url().trim()))
                        .equals(key));
    }

    /**
     * True when the discovered next URL is the current page again:
     * identical, or the same host+path with only a page=1 parameter —
     * the implicit first page of a 1-indexed site (observed live on
     * europa.eu, US-12.4).
     */
    private static boolean isCurrentPageAgain(String nextUrl, String currentUrl) {
        if (nextUrl.equals(currentUrl)) {
            return true;
        }
        try {
            java.net.URI next = java.net.URI.create(nextUrl);
            java.net.URI cur = java.net.URI.create(currentUrl);
            if (!java.util.Objects.equals(next.getHost(), cur.getHost())
                    || !next.getPath().equals(cur.getPath())) {
                return false;
            }
            String query = next.getQuery();
            if (query == null) {
                return false;
            }
            for (String param : query.split("&")) {
                if (param.equalsIgnoreCase("page=1")) {
                    return true;
                }
            }
            return false;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** The extracted items as JSON (US-06.4 artifact) — escaping comes
     * from the shared {@link ContentStoreService#jsonEscape} so the
     * application's JSON emitters stay identical (US-06.4/12.5 review
     * finding: a third hand-rolled emitter was drifting). */
    private static String itemsJson(List<NewsEventItem> items) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < items.size(); i++) {
            NewsEventItem item = items.get(i);
            if (i > 0) {
                sb.append(",");
            }
            sb.append("{\"title\":\"").append(ContentStoreService.jsonEscape(item.title()))
                    .append("\",\"kind\":\"").append(ContentStoreService.jsonEscape(item.kind()))
                    .append("\",\"description\":")
                    .append(jsonValue(item.description()))
                    .append(",\"date\":").append(jsonValue(item.date()))
                    .append(",\"url\":").append(jsonValue(item.url()))
                    .append("}");
        }
        return sb.append("]").toString();
    }

    /** A JSON string literal, or the JSON null for a null/absent value. */
    private static String jsonValue(String s) {
        return s == null ? "null" : "\"" + ContentStoreService.jsonEscape(s) + "\"";
    }

    /** SIMPLIFY (US-07 slice): + cleanse to action/data elements. */
    private String simplify(Address address) {
        var scrape = pipeline.scrapePort.scrape(address);
        pipeline.writeArtifact(address, "WEBSCRAPE", "listing-html",
                "scrape.html", scrape.html());
        var simplified = pipeline.simplifyPort.simplify(scrape.html());
        Path artifact = pipeline.writeArtifact(address, "SIMPLIFY", "simplified-html",
                "simplified.html", simplified.html());
        return "SIMPLIFY: " + scrape.sizeBytes() + " -> " + simplified.sizeBytes()
                + " bytes (cap 102400) -> " + artifact;
    }

    /** ANALYZE (US-12/13/17 slice): + pagination, selector, capabilities. */
    private String analyze(Address address) {
        var scrape = pipeline.scrapePort.scrape(address);
        pipeline.writeArtifact(address, "WEBSCRAPE", "listing-html",
                "scrape.html", scrape.html());
        var simplified = pipeline.simplifyPort.simplify(scrape.html());
        pipeline.writeArtifact(address, "SIMPLIFY", "simplified-html",
                "simplified.html", simplified.html());
        PaginationInfo pagination = pipeline.paginationDetector.detect(simplified.html());
        PipelineService.SelectorSelection selection =
                pipeline.selectSelector(address, simplified.html());
        PipelineService.CapabilityDetection capabilities =
                pipeline.detectCapabilities(address, simplified.html());
        // Persist the detection (US-17; deferred slice-3 review finding):
        // the flags used to live only in this method's local variable.
        addressCatalog.updateCapabilities(address.id(), capabilities.flags());
        return "ANALYZE: classification=" + selection.analysis().classification()
                + ", selector=" + selection.selector() + " (" + selection.source() + ")"
                + ", pagination=" + pagination.paginated()
                + ", capabilities=" + capabilities.flags();
    }

    /** FILTER (US-17/US-06.6 slice): + filter extraction and application.
     * The chain's filter spec (owner decision 2026-11-12), when present,
     * is applied offline instead of an AI-chosen criterion. */
    private String filter(Address address, FilterSpec spec) {
        var scrape = pipeline.scrapePort.scrape(address);
        pipeline.writeArtifact(address, "WEBSCRAPE", "listing-html",
                "scrape.html", scrape.html());
        var simplified = pipeline.simplifyPort.simplify(scrape.html());
        pipeline.writeArtifact(address, "SIMPLIFY", "simplified-html",
                "simplified.html", simplified.html());
        PipelineService.SelectorSelection selection =
                pipeline.selectSelector(address, simplified.html());
        PipelineService.CapabilityDetection capabilities =
                pipeline.detectCapabilities(address, simplified.html());
        Address flagged = address.withCapabilities(capabilities.flags());
        // Persist the detection (US-17; deferred slice-3 review finding):
        // the FILTERING flag used to live only in this method's local
        // variable. The merge use case keeps flags a later run detects.
        addressCatalog.updateCapabilities(address.id(), capabilities.flags());
        if (!flagged.hasCapability(AddressCapability.FILTERING)) {
            return "FILTER: skipped — address not flagged with the filtering capability "
                    + "(detected: " + capabilities.flags() + ")";
        }
        PipelineService.FilterOutcome outcome =
                pipeline.extractFilters(flagged, simplified.html());
        StringBuilder summary = new StringBuilder("FILTER: ")
                .append(outcome.filters().size()).append(" definitions (")
                .append(outcome.source() == null ? "no zone" : outcome.source()).append(")");
        if (!outcome.filters().isEmpty()) {
            PipelineService.FilterApplication application =
                    pipeline.applyFilter(flagged, selection, outcome, spec);
            if (application.field() != null) {
                summary.append("; applied ").append(application.field()).append("=")
                        .append(application.value())
                        .append(" -> ").append(application.items().size()).append(" items");
            } else {
                summary.append("; not applied (").append(application.note()).append(")");
            }
        }
        return summary.toString();
    }

    /** PRINT / CHAIN: the whole pipeline (WEBSCRAPE - SIMPLIFY -
     * ANALYZE - FILTER - PRINT), with the per-task parameters of
     * US-08.3 (issue #35). The readable report is persisted as a
     * PRINT task output in the content tree (US-11) and named after
     * the chain when the run is a chain's step. The chain's filter
     * spec, when the chain defines one, governs the run's filter
     * application. */
    private String fullChain(Address address, Map<String, String> parameters,
                             String chainName, FilterSpec chainFilterSpec) {
        // Whitelist, not lookup-and-ignore: an unknown key would
        // otherwise be silently dead configuration — the exact
        // fail-open the US-08 review disqualified (a typo like
        // 'max-ites' would just do nothing).
        for (String key : parameters.keySet()) {
            if (!"max-items".equals(key) && !"max-pages".equals(key)) {
                throw new IllegalArgumentException("Task type PRINT/CHAIN accepts only "
                        + "max-items and max-pages (got: " + key + ")");
            }
        }
        int maxItems = intParameter(parameters, "max-items", 50, 1, 10_000);
        int maxPages = intParameter(parameters, "max-pages", 10, 1, 100);
        PipelineResult result = pipeline.run(address, maxItems, maxPages, chainFilterSpec);
        String report = PipelineReport.render(
                chainName != null ? chainName : "PRINT", result);
        Path reportArtifact = pipeline.writeArtifact(address, "PRINT", "report",
                "report.txt", report);
        return "CHAIN: " + result.items().size() + " items, pages=" + result.pagesScraped()
                + ", classification=" + (result.analysis() == null ? "n/a" : "done")
                + ", capabilities=" + result.capabilities()
                + ", scrape=" + result.scrapeArtifact()
                + ", simplified=" + result.simplifyArtifact()
                + ", report=" + reportArtifact;
    }

    /** One bounded integer parameter, or its default when absent. */
    private int intParameter(Map<String, String> parameters, String key,
                             int defaultValue, int min, int max) {
        String raw = parameters.get(key);
        if (raw == null) {
            return defaultValue;
        }
        int value;
        try {
            value = Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "Parameter '" + key + "' must be an integer, got: " + raw);
        }
        if (value < min || value > max) {
            throw new IllegalArgumentException("Parameter '" + key
                    + "' must be between " + min + " and " + max + ", got: " + value);
        }
        return value;
    }

    /**
     * US-12.5 (issue #68): the effective extraction cap — the task's
     * max-items parameter, else the externalized default
     * (neis.extraction.max-items), else no limit. Note the asymmetry
     * (documented, intentional): the task parameter is bounded
     * 1..10000 while the config default accepts any positive integer.
     */
    private int maxItemsCap(Map<String, String> parameters) {
        if (parameters.containsKey("max-items")) {
            return intParameter(parameters, "max-items", 0, 1, 10_000);
        }
        return configuredMaxItems == null ? Integer.MAX_VALUE : configuredMaxItems;
    }
}
