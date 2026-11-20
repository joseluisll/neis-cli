package es.jllopez.neis.application;

import es.jllopez.neis.domain.model.Address;
import es.jllopez.neis.domain.model.AddressCapability;
import es.jllopez.neis.domain.model.FilterSpec;
import es.jllopez.neis.domain.model.NewsEventItem;
import es.jllopez.neis.domain.model.PaginationInfo;
import es.jllopez.neis.domain.model.ScrapeResult;
import es.jllopez.neis.domain.port.NewsEventStorePort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(TaskWorkService.class);

    /** Safety guard for the pagination iteration (US-12.4). */
    private static final int MAX_PAGES = 10;

    private final PipelineService pipeline;
    private final AddressCatalogService addressCatalog;
    private final NewsEventStorePort newsEventStore;
    private final es.jllopez.neis.domain.port.FeedSourcePort feedSource;
    private final es.jllopez.neis.domain.port.WebpageTypeCatalogPort webpageTypes;
    private final PreconfiguredFilters preconfiguredFilters;
    /** Page-specific clearing selectors (US-06.7, issue #75). */
    private final ClearingSelectors clearingSelectors;
    /** Externalized default cap (US-12.5): null = no limit. Volatile:
     * the US-16.5 edit path may update it while runs use it. */
    private volatile Integer configuredMaxItems;

    public TaskWorkService(PipelineService pipeline, AddressCatalogService addressCatalog,
                           NewsEventStorePort newsEventStore,
                           es.jllopez.neis.domain.port.FeedSourcePort feedSource,
                           es.jllopez.neis.domain.port.WebpageTypeCatalogPort webpageTypes,
                           PreconfiguredFilters preconfiguredFilters,
                           ClearingSelectors clearingSelectors,
                           @org.springframework.beans.factory.annotation.Value(
                                   "${neis.extraction.max-items:}") String configuredMaxItems) {
        this.pipeline = pipeline;
        this.addressCatalog = addressCatalog;
        this.newsEventStore = newsEventStore;
        this.feedSource = feedSource;
        this.webpageTypes = webpageTypes;
        this.preconfiguredFilters = preconfiguredFilters;
        this.clearingSelectors = clearingSelectors;
        this.configuredMaxItems = parseMaxItems(configuredMaxItems);
    }

    /**
     * Applies a persisted/live override of {@code
     * neis.extraction.max-items} (US-16.5 edit path): the same rules as
     * the boot configuration — null clears the cap, a present value
     * must be a positive integer. Takes effect for every subsequent
     * extraction in this process.
     */
    public void updateMaxItems(Integer maxItems) {
        if (maxItems != null && maxItems < 1) {
            throw new IllegalArgumentException(
                    "neis.extraction.max-items must be >= 1, got: " + maxItems);
        }
        this.configuredMaxItems = maxItems;
        log.info("EXTRACTION cap override applied: {}", maxItems == null ? "none" : maxItems);
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
        return execute(taskCode, address, parameters, chainName, null, null);
    }

    /**
     * Same as {@link #execute(String, Address, Map)} with the name of
     * the chain whose step is being executed (null for single task
     * runs; the PRINT report artifact is titled with it), the chain's
     * filter spec (owner decision 2026-11-12): the criterion the chain
     * DEFINITION carries, applied offline by the FILTER application of
     * this run — null when the chain defines no filter — and the
     * owning execution's id (US-14.2 provenance: persisted with every
     * extracted item; null in tests).
     */
    public String execute(String taskCode, Address address,
                          Map<String, String> parameters, String chainName,
                          FilterSpec chainFilterSpec, String executionId) {
        return switch (taskCode) {
            case "WEBSCRAPE" -> {
                // US-12.5: the step accepts an optional max-items cap
                // (1..10000); absent, the externalized default applies;
                // absent too, extraction is unlimited. US-12.6: an
                // optional selector override replaces the preconfigured
                // selector for this run; it is validated (US-12.3)
                // before anything runs. US-06.5: an optional connector
                // override pins the retrieval strategy (fixtures/http/
                // rendered) for this run instead of the address's
                // webpage type; an unknown value fails the run loud.
                // US-06.6: optional filter.<name>=<value> parameters
                // (or the address's preconfigured filters) are applied
                // BEFORE extraction — URL parameters for static pages,
                // operated controls for rendered ones. US-06.8: a
                // FEED-classified address is parsed directly (no
                // selector, no filters, no HTML scraping when the feed
                // suffices).
                requireNoParameters(taskCode, parameters, "max-items", "selector",
                        "connector", "filter.");
                if (isFeedSource(address)) {
                    String filterOverride = firstFilterParam(parameters);
                    if (parameters.get("selector") != null || filterOverride != null) {
                        throw new IllegalArgumentException(
                                (filterOverride != null ? "Filter parameter '"
                                        + filterOverride + "'" : "A selector override")
                                        + " does not apply to feed sources (US-06.6/06.8) — the feed of "
                                        + address.id() + " is parsed directly; remove the parameter");
                    }
                    // A FEED address's preconfigured set is dead
                    // configuration (US-06.6 review finding: it was
                    // silently ignored) — refuse it loud, the same rule
                    // as the per-run filter refusal above.
                    if (preconfiguredFilters.find(address.id()).isPresent()) {
                        throw new IllegalArgumentException("Address " + address.id()
                                + " has preconfigured WEBSCRAPE filters, but its feed "
                                + "source is parsed directly (US-06.6/06.8) — filters "
                                + "do not apply to feeds; remove the preconfigured set");
                    }
                    // A connector override is silently IGNORED here, not
                    // refused (US-06.10 review finding on #100): the feed
                    // IS the retrieval — there is no strategy to pin — but
                    // the user must see that the parameter did nothing
                    // (one operational line; the run proceeds).
                    String connectorOverride = parameters.get("connector");
                    if (connectorOverride != null && !connectorOverride.isBlank()) {
                        log.info("WEBSCRAPE {}: the connector override '{}' is IGNORED — "
                                + "the feed of {} is parsed directly (US-06.8); the feed "
                                + "path has no retrieval strategy to pin",
                                taskCode, connectorOverride.trim(), address.id());
                    }
                    yield feedScrape(address, maxItemsCap(parameters), executionId);
                }
                yield scrape(address, maxItemsCap(parameters),
                        parameters.get("selector"), executionId,
                        parameters.get("connector"), filterSet(address, parameters));
            }
            case "SIMPLIFY" -> {
                // US-07.6: an optional variant parameter (full/economy/
                // lean) pins the tier for this task/chain element; the
                // default (absent) is full.
                // US-06.14: an optional 'connector' pins the scrape
                // strategy for the whole reprocessing run (a cached
                // chain's steps re-scrape through the same strategy)
                requireNoParameters(taskCode, parameters, "variant", "connector");
                yield simplify(address, executionId,
                        parameters.get("variant"), parameters.get("connector"));
            }
            case "ANALYZE" -> {
                requireNoParameters(taskCode, parameters, "connector");
                yield analyze(address, executionId, parameters.get("connector"));
            }
            case "AI" -> {
                // US-13.1..4: the AI task runs the classification
                // (news/event/other) and, when the page's kind demands
                // it, the news/events selector identification over the
                // simplified content — the same prompt catalog and
                // selection machinery the ANALYZE step uses, exposed as
                // a standalone schedulable/chainable task type. The
                // provider and model are configuration (US-13.1), never
                // code; with no AI configured the step fails loud
                // (the selector-identification machinery has its own
                // heuristic fallbacks, but an AI TASK that ran without
                // an AI would silently be something else).
                requireNoParameters(taskCode, parameters);
                yield aiTask(address, executionId, parameters.get("connector"));
            }
            case "FILTER" -> {
                // A chain's FILTER step carries the filter spec as its
                // parameters ("field"/"value"); the executor threads the
                // parsed spec to every filter-applying step of the run.
                FilterSpec spec = chainFilterSpec != null
                        ? chainFilterSpec : FilterSpec.of(parameters);
                requireNoParameters(taskCode, parameters,
                        FilterSpec.FIELD_KEY, FilterSpec.VALUE_KEY, "connector");
                yield filter(address, spec, executionId,
                        parameters.get("connector"));
            }
            case "PRINT", "CHAIN" -> fullChain(address, parameters, chainName,
                    chainFilterSpec, executionId);
            default -> throw new IllegalArgumentException(
                    "Unknown task type: " + taskCode
                            + " (known: WEBSCRAPE, SIMPLIFY, ANALYZE, AI, FILTER, PRINT, CHAIN)");
        };
    }

    private void requireNoParameters(String taskCode, Map<String, String> parameters,
                                     String... allowedKeys) {
        for (String key : parameters.keySet()) {
            boolean allowed = false;
            for (String allowedKey : allowedKeys) {
                // A trailing dot marks a PREFIX allowance: the
                // WEBSCRAPE filter parameters are filter.<name>
                // (US-06.6) — the name is user data, not vocabulary.
                boolean prefixMatch = allowedKey.endsWith(".")
                        && key.startsWith(allowedKey) && key.length() > allowedKey.length();
                allowed |= prefixMatch || allowedKey.equals(key);
            }
            if (!allowed) {
                throw new IllegalArgumentException("Task type " + taskCode
                        + " takes no such parameter (got: " + key + "; allowed: "
                        + (allowedKeys.length == 0 ? "none" : String.join(", ", allowedKeys))
                        + ")");
            }
        }
    }

    /** The first filter.* parameter key, for refusal messages. */
    private static String firstFilterParam(Map<String, String> parameters) {
        return parameters.keySet().stream().filter(k -> k.startsWith("filter."))
                .filter(k -> k.length() > "filter.".length())
                .findFirst().orElse(null);
    }

    /**
     * The filter set this WEBSCRAPE run applies (US-06.6): the per-run
     * {@code filter.<name>} overrides win; absent, the address's
     * preconfigured filter set applies (US-12.6 override semantics);
     * neither present, the listing is fetched unfiltered.
     */
    private Map<String, String> filterSet(Address address,
                                          Map<String, String> parameters) {
        Map<String, String> overrides = new java.util.LinkedHashMap<>();
        java.util.Set<String> seenNames = new java.util.HashSet<>();
        for (var e : parameters.entrySet()) {
            if (e.getKey().startsWith("filter.") && e.getKey().length() > "filter.".length()) {
                String name = e.getKey().substring("filter.".length()).trim();
                if (name.isEmpty()) {
                    throw new IllegalArgumentException(
                            "Empty filter name (filter.=...) — name the filter, e.g. "
                                    + "filter.category=plenary (US-06.6)");
                }
                if (e.getValue() == null || e.getValue().isBlank()) {
                    throw new IllegalArgumentException("The filter '" + name
                            + "' has an empty value — remove the parameter instead of "
                            + "filtering to nothing (US-06.6)");
                }
                // Case-INSENSITIVE duplicate detection (US-06.6 review
                // finding: the old putIfAbsent almost never fired — two
                // per-run parameters carry different map keys, so a
                // collision needed keys that TRIM to the same name
                // ('filter.x' vs 'filter.x '), and case-differing keys
                // ('filter.X' vs 'filter.x') slipped through). The
                // check is now case-insensitive; the name itself keeps
                // its original case in the applied set.
                if (!seenNames.add(name.toLowerCase(java.util.Locale.ROOT))) {
                    throw new IllegalArgumentException("Duplicate filter '" + name
                            + "' (case differs only) — refused (US-12.3 rule)");
                }
                overrides.put(name, e.getValue().trim());
            }
        }
        if (!overrides.isEmpty()) {
            return overrides;
        }
        return preconfiguredFilters.find(address.id()).orElse(Map.of());
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
     * ANALYZE's job (AI or fallbacks). US-12.3: the selector is
     * validated (syntax + at least one match) before extraction — this
     * step is STRICT; the CHAIN/PRINT path keeps its own fallback
     * strategy (AI/heuristic re-identification) and tolerates empty
     * matches instead.
     */
    /**
     * Whether this address resolves to the FEED retrieval strategy
     * (US-06.8): its classified webpage type maps to the feed strategy
     * in the taxonomy. An unclassified address is never a feed source
     * (it takes the ordinary scrape path); the per-run connector
     * override does not re-route feeds — the feed IS the retrieval.
     */
    private boolean isFeedSource(Address address) {
        return webpageTypes.byId(address.webpageType())
                .map(type -> es.jllopez.neis.domain.model.WebpageType.STRATEGY_FEED
                        .equals(type.strategy()))
                .orElse(false);
    }

    /**
     * Whether this address resolves to the RENDERED retrieval strategy
     * (US-06.5). Used by the pagination loop to flag the documented
     * SPA next-page gap (US-06.10 review finding): next pages always
     * fetch over plain HTTP, which on a client-rendered listing serves
     * the unrendered shell — the run must record that, not fail
     * silently.
     */
    private boolean isRenderedSource(Address address) {
        return webpageTypes.byId(address.webpageType())
                .map(type -> es.jllopez.neis.domain.model.WebpageType.STRATEGY_RENDERED
                        .equals(type.strategy()))
                .orElse(false);
    }

    /**
     * The feed retrieval path (US-06.8, issue #86): fetch the feed
     * document, write it as the run's artifact and provenance source,
     * parse the entries into standard records and persist them — the
     * same repository path, provenance and cap as the scrape path,
     * without any HTML scraping when the feed suffices.
     */
    private String feedScrape(Address address, int maxItems, String executionId) {
        // The raw bytes travel to the parse verbatim: an XML document
        // carries its own charset declaration, so decoding for the
        // artifact happens with that declaration honored
        // (toDocumentText), and Rome re-detects it for the parse.
        byte[] feedBytes = feedSource.fetch(address);
        String xml = feedSource.toDocumentText(feedBytes);
        Path artifact = pipeline.writeArtifact(address, "WEBSCRAPE", "feed-xml",
                "feed.xml", xml, executionId);
        List<NewsEventItem> items = feedSource.parse(feedBytes, address.id(),
                address.itemKind(), maxItems, address.url());
        if (items.isEmpty()) {
            throw new IllegalArgumentException("Feed " + address.url()
                    + " yielded no entries — WEBSCRAPE over " + address.id()
                    + " aborted");
        }
        Path itemsArtifact = pipeline.writeArtifact(address, "WEBSCRAPE",
                "items-json", "items.json", itemsJson(items), executionId);
        // US-14.1/14.2: the same repository persistence and provenance
        // as the scrape path — the feed document is the content record
        // the items came from.
        newsEventStore.saveAll(address.id(), address.itemKind(), items,
                executionId,
                pipeline.contentRecordId(address, "WEBSCRAPE", "feed.xml"));
        String capNote = maxItems == Integer.MAX_VALUE ? "no cap" : "cap " + maxItems;
        return "WEBSCRAPE (feed): " + items.size() + " item(s) -> " + itemsArtifact
                + "; feed document -> " + artifact + "; " + capNote;
    }

    private String scrape(Address address, int maxItems, String selectorOverride,
                          String executionId, String connectorOverride,
                          Map<String, String> filters) {
        // US-06.6 (issue #73): the filter set (per-run override or the
        // address's preconfigured set) is applied BEFORE extraction —
        // URL query parameters for static pages, operated filter
        // controls for rendered pages; the applied set is recorded with
        // the capture metadata (US-11.4).
        var scrape = filters == null || filters.isEmpty()
                ? pipeline.scrapePort.scrape(address, connectorOverride)
                : pipeline.scrapePort.scrapeWithFilters(address, filters, connectorOverride);
        // US-06.14 (issue #138): a CACHED fetch carries its provenance
        // on the result — the outcome line names the reuse and the
        // original scrape, never a silent stale serve.
        String cachedNote = scrape.sourceNote() == null ? ""
                : "; CACHED reuse — " + scrape.sourceNote();
        // Obstruction clearing (US-06.7, issue #75): popups, banners,
        // ads and the configured selectors are removed BEFORE the
        // artifact is written or extraction runs; the removal record
        // lands on the capture metadata (US-11.4).
        var cleared = pipeline.clearObstructions(address, scrape.html());
        Path artifact = pipeline.writeArtifact(address, "WEBSCRAPE", "listing-html",
                "scrape.html", cleared.html(), executionId);
        // US-06.9: the pruned element tree + catalog + change digest
        // (page 1; pagination pages are analyzed below per page).
        pipeline.analyzeDom(address, cleared.html(), executionId);
        pipeline.captureWithFilters(address, address.url(), filters,
                PipelineService.clearingRecord(cleared));
        String filtersNote = filters == null || filters.isEmpty() ? ""
                : "; filters: " + filterSummary(filters);
        PaginationInfo pagination = pipeline.paginationDetector.detect(cleared.html());

        // US-12.6 (issue #72): a selector override replaces the
        // preconfigured selector for this run; preconfigured selectors
        // remain the default whenever no override applies. An
        // EXPLICITLY EMPTY override is refused — no silent fallback to
        // the preconfigured selector (US-12.3/12.6 review finding,
        // 2026-11-14).
        if (selectorOverride != null && selectorOverride.isBlank()) {
            throw new IllegalArgumentException(
                    "Empty selector override (US-12.6): remove the parameter to "
                            + "use the preconfigured selector, or provide a selector");
        }
        String selectorOverrideTrimmed = selectorOverride == null
                ? null : selectorOverride.trim();
        Optional<String> selector = selectorOverrideTrimmed != null
                ? Optional.of(selectorOverrideTrimmed)
                : pipeline.preconfiguredSelectors.find(address.id(), address.itemKind());
        String selectorEmptyNote = selector.isEmpty()
                ? "; no preconfigured selector for " + address.id()
                        + " — item extraction skipped (US-06.4)" : "";
        String clearNote = cleared.removedCount() > 0
                ? "; cleared " + cleared.removedCount() + " obstructing element(s)"
                : "";
        if (selector.isEmpty()) {
            return "WEBSCRAPE: " + cleared.sizeBytes() + " bytes -> " + artifact
                    + filtersNote + clearNote + selectorEmptyNote + cachedNote
                    + "; paginated: "
                    + pagination.paginated() + " (" + pagination.evidence() + ")";
        }
        // US-12.3 (issue #48): validation BEFORE execution — a selector
        // that does not parse never runs; the abort is loud, no silent
        // fallback (US-12.6).
        if (!pipeline.extractionPort.isSyntaxValid(selector.get())) {
            throw new IllegalArgumentException("Selector '" + selector.get()
                    + "' is invalid (does not parse; US-12.3) — WEBSCRAPE over "
                    + address.id() + " aborted"
                    + (selectorOverrideTrimmed != null ? " (override)" : ""));
        }

        List<NewsEventItem> items = new ArrayList<>(pipeline.extractionPort.extract(
                cleared.html(), selector.get(), address.itemKind(), address.id(),
                maxItems, address.url()));
        // US-12.3: a selector is only valid if it selects at least one
        // element in the live content — an empty match aborts the run
        // with a clear error instead of silently extracting nothing.
        if (items.isEmpty()) {
            throw new IllegalArgumentException("Selector '" + selector.get()
                    + "' matched no elements on " + address.url()
                    + " (US-12.3 validation) — WEBSCRAPE over " + address.id()
                    + " aborted");
        }
        int pages = 1;
        List<String> notes = new ArrayList<>();
        java.util.Set<String> visited = new java.util.HashSet<>();
        String currentUrl = address.url().trim();
        visited.add(currentUrl);
        String nextUrl = items.size() < maxItems
                ? pipeline.extractionPort.nextPageUrl(cleared.html(), currentUrl)
                : null;
        boolean truncatedByGuard = false;
        boolean warnedSpaNextPage = false;
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
            if (!warnedSpaNextPage && isRenderedSource(address)) {
                // US-06.10 review finding: the next-page fetch is the
                // documented SPA gap — scrapeUrl always goes over plain
                // HTTP, so a client-rendered listing serves its
                // unrendered shell. The run must RECORD it, not just
                // document it in the router javadoc.
                warnedSpaNextPage = true;
                log.warn("WEBSCRAPE {}: address classified '{}' (rendered) but SPA "
                                + "next-page fetching is unsupported — page {} is fetched "
                                + "over plain HTTP and may return the unrendered shell",
                        address.id(), address.webpageType(), pages + 1);
                notes.add("SPA next page fetched over plain HTTP (address is "
                        + address.webpageType() + ", rendered pagination unsupported) "
                        + "— content may be an unrendered shell");
            }
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
            var pageCleared = pipeline.clearObstructions(address, page.html());
            pipeline.writeArtifact(address, "WEBSCRAPE", "page-html",
                    "scrape-page" + pages + ".html", pageCleared.html(), executionId);
            // US-06.9: per-page element catalog + digest — each page
            // keeps its OWN catalog artifact (review non-blocking
            // finding: one shared name was overwritten per page, last
            // page wins); the capture digest follows page 1's tree.
            pipeline.analyzeDom(address, pageCleared.html(), executionId, pages);
            int before = items.size();
            for (NewsEventItem item : pipeline.extractionPort.extract(pageCleared.html(),
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
                    ? pipeline.extractionPort.nextPageUrl(pageCleared.html(), nextUrl)
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
                "items-json", "items.json", itemsJson(items), executionId);
        // US-14.1 (issue #55): the extracted items are persisted as
        // repository entities, not only as content files — idempotent
        // upsert per (address, detail URL). US-14.2 (issue #56): each
        // record references the execution that extracted it and the
        // scrape.html content it came from.
        newsEventStore.saveAll(address.id(), address.itemKind(), items,
                executionId,
                pipeline.contentRecordId(address, "WEBSCRAPE", "scrape.html"),
                scrape.sourceNote() != null);
        // 'cap M' states that extraction stopped at the cap — the count
        // is the recorded truth; whether MORE elements existed beyond the
        // cap is not observable through the extraction contract (review
        // finding 2026-11-14: 'reached' implied truncation, which a
        // listing of exactly maxItems items cannot prove).
        String capNote = maxItems == Integer.MAX_VALUE ? "no cap"
                : "cap " + maxItems;
        String iterationNote = notes.isEmpty() ? "" : "; " + String.join("; ", notes);
        return "WEBSCRAPE: " + cleared.sizeBytes() + " bytes -> " + artifact
                + filtersNote + clearNote + cachedNote
                + "; items extracted: " + items.size() + " (" + capNote
                + "; selector '" + selector.get() + "') -> " + itemsArtifact
                + "; pages: " + pages + "; paginated: " + pagination.paginated()
                + " (" + pagination.evidence() + ")" + iterationNote;
    }

    /** Compact filter summary for the outcome line: k=v, k2=v2. */
    private static String filterSummary(Map<String, String> filters) {
        return filters.entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue())
                .reduce((a, b) -> a + ", " + b).orElse("");
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

    /** SIMPLIFY (US-07 slice): + cleanse to action/data elements.
     * US-07.6: the optional {@code variant} parameter (full/economy/
     * lean) pins the tier of the rendered output; the variant's
     * hashed-URL side table (lean tier) lands on the capture metadata
     * (US-11.4, never-wipes). */
    private String simplify(Address address, String executionId,
                            String variant, String connectorOverride) {
        var scrape = pipeline.scrapePort.scrape(address, connectorOverride);
        // US-06.7 (review blocking finding B3): every re-scrape that
        // (re)writes the stored scrape.html goes through the clearing,
        // so a SIMPLIFY/ANALYZE/FILTER run never restores obstructions
        // into the artifact the capture metadata describes.
        var cleared = pipeline.clearObstructions(address, scrape.html());
        pipeline.writeArtifact(address, "WEBSCRAPE", "listing-html",
                "scrape.html", cleared.html(), executionId);
        String tier = variant == null || variant.isBlank()
                ? es.jllopez.neis.domain.model.SimplifyVariant.FULL
                : variant.trim();
        var simplified = pipeline.simplifyPort.simplify(cleared.html(), tier);
        pipeline.recordSimplifySideTable(address, cleared.html(), tier, executionId);
        Path artifact = pipeline.writeSimplifiedArtifact(address, "SIMPLIFY", "simplified-html",
                "simplified.html", simplified.html(), executionId);
        return "SIMPLIFY(" + tier + "): " + cleared.sizeBytes() + " -> "
                + simplified.sizeBytes() + " bytes (cap 102400) -> " + artifact;
    }

    /** ANALYZE (US-12/13/17 slice): + pagination, selector, capabilities. */
    private String analyze(Address address, String executionId,
                           String connectorOverride) {
        var scrape = pipeline.scrapePort.scrape(address, connectorOverride);
        var cleared = pipeline.clearObstructions(address, scrape.html());
        pipeline.writeArtifact(address, "WEBSCRAPE", "listing-html",
                "scrape.html", cleared.html(), executionId);
        var simplified = pipeline.simplifyPort.simplify(cleared.html());
        pipeline.writeSimplifiedArtifact(address, "SIMPLIFY", "simplified-html",
                "simplified.html", simplified.html(), executionId);
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

    /**
     * AI task (US-13.1..4, issues #50..53): classification of the
     * simplified content into news/event/other (US-13.2) and
     * identification of the news/events selector (US-13.3/.4) —
     * whether raw or simplified input, the identification runs on the
     * simplified HTML5 the SIMPLIFY tier produces (the raw listing
     * HTML5 is first simplified; same path as ANALYZE). The verdict
     * and the identified selector are recorded as capture metadata
     * (US-13.2, V18 classification column). A valid identified
     * selector is persisted with the address (the same working-selector
     * store ANALYZE populates), so a chained extraction task reuses it.
     */
    private String aiTask(Address address, String executionId,
                          String connectorOverride) {
        // Fails loud when no AI is configured (US-13.1: the AI task is
        // an AI task — silently degrading to heuristics would mislead).
        var ai = pipeline.aiAssistant();
        var scrape = pipeline.scrapePort.scrape(address, connectorOverride);
        var cleared = pipeline.clearObstructions(address, scrape.html());
        pipeline.writeArtifact(address, "WEBSCRAPE", "listing-html",
                "scrape.html", cleared.html(), executionId);
        var simplified = pipeline.simplifyPort.simplify(cleared.html());
        pipeline.writeSimplifiedArtifact(address, "SIMPLIFY", "simplified-html",
                "simplified.html", simplified.html(), executionId);

        PipelineService.SelectorSelection selection =
                pipeline.aiIdentify(address, simplified.html(), ai,
                        note -> { /* the AI task reports the outcome line */ });
        if (selection == null) {
            throw new IllegalStateException(
                    "The AI task did not yield a usable result — the AI answers"
                    + " did not validate (see the run log; no heuristic fallback"
                    + " runs for an AI task)");
        }

        // US-13.2: the classification is recorded as metadata — the
        // JSON verdict (classification + selector + source) lands on
        // the capture metadata (V18 classification column, never-wipes).
        String verdictJson = new StringBuilder("{\"classification\":\"")
                .append(ContentStoreService.jsonEscape(
                        selection.analysis().classification()))
                .append("\",\"selector\":\"")
                .append(ContentStoreService.jsonEscape(selection.selector()))
                .append("\",\"source\":\"")
                .append(ContentStoreService.jsonEscape(selection.source()))
                .append("\"}").toString();
        pipeline.captureMetadata(address, address.url(), null, null, null,
                null, null, verdictJson);
        return "AI: classification=" + selection.analysis().classification()
                + ", selector=" + selection.selector() + " (" + selection.source() + ")";

    }

    /** FILTER (US-17/US-06.6 slice): + filter extraction and application.
     * The chain's filter spec (owner decision 2026-11-12), when present,
     * is applied offline instead of an AI-chosen criterion. */
    private String filter(Address address, FilterSpec spec, String executionId,
                          String connectorOverride) {
        var scrape = pipeline.scrapePort.scrape(address, connectorOverride);
        var cleared = pipeline.clearObstructions(address, scrape.html());
        pipeline.writeArtifact(address, "WEBSCRAPE", "listing-html",
                "scrape.html", cleared.html(), executionId);
        var simplified = pipeline.simplifyPort.simplify(cleared.html());
        pipeline.writeSimplifiedArtifact(address, "SIMPLIFY", "simplified-html",
                "simplified.html", simplified.html(), executionId);
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
                    pipeline.applyFilter(flagged, selection, outcome, spec, executionId);
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
                             String chainName, FilterSpec chainFilterSpec,
                             String executionId) {
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
        // US-06.6 (review finding): the chain's SCRAPE stage consults
        // the address's preconfigured filter set — PRINT/CHAIN used to
        // silently ignore it. The chain whitelist above admits no
        // filter.* parameters, so the preconfigured catalog is the only
        // filter source on this path.
        PipelineResult result = pipeline.run(address, maxItems, maxPages,
                chainFilterSpec, executionId,
                preconfiguredFilters.find(address.id()).orElse(java.util.Map.of()));
        String report = PipelineReport.render(
                chainName != null ? chainName : "PRINT", result);
        Path reportArtifact = pipeline.writeArtifact(address, "PRINT", "report",
                "report.txt", report, executionId);
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
