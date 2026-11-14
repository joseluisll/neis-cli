package es.jllopez.neis.application;

import es.jllopez.neis.domain.model.Address;
import es.jllopez.neis.domain.model.AddressCapability;
import es.jllopez.neis.domain.model.AddressType;
import es.jllopez.neis.domain.model.FilterDefinition;
import es.jllopez.neis.domain.model.FilterRequestOption;
import es.jllopez.neis.domain.model.FilterSpec;
import es.jllopez.neis.domain.model.NewsEventItem;
import es.jllopez.neis.domain.model.PageAnalysis;
import es.jllopez.neis.domain.model.PaginationInfo;
import es.jllopez.neis.domain.model.ScrapeResult;
import es.jllopez.neis.domain.model.SimplifiedContent;
import es.jllopez.neis.domain.model.StoredSelectors;
import es.jllopez.neis.domain.port.AiAssistantPort;
import es.jllopez.neis.domain.port.ContentScrapePort;
import es.jllopez.neis.domain.port.NewsEventStorePort;
import es.jllopez.neis.domain.port.SelectorExtractionPort;
import es.jllopez.neis.domain.port.SelectorStorePort;
import es.jllopez.neis.domain.port.SimplifyPort;
import es.jllopez.neis.domain.service.ExposedFilterDetector;
import es.jllopez.neis.domain.service.PaginationDetector;
import es.jllopez.neis.domain.service.UrlSyntaxValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Use case: the SSAFP stage pipeline — WEBSCRAPE - SIMPLIFY - ANALYZE -
 * FILTER - PRINT — behind the executor's task types (US-05/US-08, ex
 * issue #90 + US-17). The task types are the pipeline's stages as
 * runnable prefixes ({@code TaskWorkService}); a configured chain
 * ({@code chain create}, e.g. {@code core-chain-ssafp}) executes them
 * in order over an address. There is no hard-coded whole-catalog
 * orchestration any more: batch runs are chain runs submitted per
 * address through {@code ExecutionService}.
 *
 * <p>ANALYZE selector strategy (owner decision 2026-11-11, extended by
 * US-12.1): a stored, previously validated selector for the address is
 * used directly and the AI is skipped entirely (fast path). Next comes
 * the configuration's preconfigured selector (US-12.1,
 * {@code neis.selectors}) — deterministic, persisted once validated.
 * Only when neither exists does the chain invoke the AI — one
 * task-specific prompt per concern from {@link AiPromptCatalog}
 * (classification, then news or events selector identification) —
 * validate the identified selector by probe extraction, and persist it
 * with the address so future runs need no AI. Static heuristic
 * selectors remain as last-resort fallback and are never persisted.</p>
 *
 * <p>Capability flagging (US-17, owner decision 2026-11-11): ANALYZE
 * detects the listing capabilities of the address — pagination and
 * filtering — via the shared AI service (one capability prompt; no
 * site-specific knowledge is ever encoded in code) and FLAGS them onto
 * the address. The page-specific data (selectors, filter values, page
 * URL pattern) is likewise AI-identified. Downstream blocks execute
 * their page-dependent functionality only when the address has been
 * flagged with the corresponding capability: the pagination-iteration
 * block runs only on PAGINATION-flagged addresses (following the
 * AI-derived page URL pattern up to the item cap), and the FILTER block
 * extracts the filter definitions only on FILTERING-flagged addresses.
 * Generic HTML5 heuristics ({@link PaginationDetector},
 * {@link ExposedFilterDetector}) are the fallback when no AI is
 * configured; without an AI-derived page URL pattern the iteration
 * block cannot address pages and is skipped.</p>
 */
@Service
public class PipelineService {

    /** Maximum items extracted per address (US-12.5 spirit). */
    private static final int MAX_ITEMS = 50;

    /** Safety guard for the pagination-iteration block. */
    private static final int MAX_PAGES = 10;

    private static final List<String> FALLBACK_NEWS_SELECTORS =
            List.of("ul.news-list li", ".news-item", ".news-entry", "article",
                    "ul li", "a[href]");
    private static final List<String> FALLBACK_EVENT_SELECTORS =
            List.of(".event-item", ".event-entry", "article", "ul li", "a[href]");

    /** Explicit, decision-level traces of the AI component (US-01.3). */
    private static final Logger log = LoggerFactory.getLogger(PipelineService.class);

    final ContentScrapePort scrapePort;
    final SimplifyPort simplifyPort;
    final SelectorExtractionPort extractionPort;
    private final SelectorStorePort selectorStore;
    final PaginationDetector paginationDetector;
    private final ExposedFilterDetector exposedFilterDetector;
    private final ObjectProvider<AiAssistantPort> aiPort;
    private final AddressCatalogService addressCatalog;
    private final UrlSyntaxValidator urlValidator;
    final PreconfiguredSelectors preconfiguredSelectors;
    private final NewsEventStorePort newsEventStore;
    final ContentStoreService contentStore;

    public PipelineService(ContentScrapePort scrapePort,
                           SimplifyPort simplifyPort,
                           SelectorExtractionPort extractionPort,
                           SelectorStorePort selectorStore,
                           PaginationDetector paginationDetector,
                           ExposedFilterDetector exposedFilterDetector,
                           ObjectProvider<AiAssistantPort> aiPort,
                           AddressCatalogService addressCatalog,
                           UrlSyntaxValidator urlValidator,
                           PreconfiguredSelectors preconfiguredSelectors,
                           NewsEventStorePort newsEventStore,
                           ContentStoreService contentStore,
                           @Value("${neis.core.work-dir:./core-work}") String workDir) {
        this.scrapePort = scrapePort;
        this.simplifyPort = simplifyPort;
        this.extractionPort = extractionPort;
        this.selectorStore = selectorStore;
        this.paginationDetector = paginationDetector;
        this.exposedFilterDetector = exposedFilterDetector;
        this.aiPort = aiPort;
        this.addressCatalog = addressCatalog;
        this.urlValidator = urlValidator;
        this.preconfiguredSelectors = preconfiguredSelectors;
        this.newsEventStore = newsEventStore;
        this.contentStore = contentStore;
    }

    /** Runs the whole pipeline for one address. */
    public PipelineResult run(Address address) {
        return run(address, MAX_ITEMS, MAX_PAGES, null);
    }

    /**
     * Runs the whole pipeline for one address with the chain's
     * per-task limits (US-08.3, issue #35): {@code maxItems} caps the
     * extracted items (US-12.5 spirit), {@code maxPages} guards the
     * pagination-iteration block.
     */
    public PipelineResult run(Address address, int maxItems, int maxPages) {
        return run(address, maxItems, maxPages, null);
    }

    /**
     * Same, with the CHAIN's filter spec (owner decision 2026-11-12):
     * when the chain definition carries a filter, the FILTER
     * application applies THAT criterion — offline, no AI in the loop —
     * instead of letting the AI choose one.
     */
    public PipelineResult run(Address address, int maxItems, int maxPages,
                              FilterSpec filterSpec) {
        // Eligibility pre-check (US-04.1/#21 + US-04.3/#23): the URL
        // must be syntactically valid and the address verified BEFORE
        // the chain uses it.
        urlValidator.validate(address.url()).ifPresent(reason -> {
            throw new IllegalArgumentException("Address " + address.id()
                    + " cannot be used — its URL is invalid: " + reason);
        });
        if (!address.isVerified()) {
            throw new IllegalStateException("Address " + address.id()
                    + " is not eligible: verification status is "
                    + address.verificationStatus()
                    + " — verify it first with 'address verify'");
        }
        // SCRAPE (US-06.1 slice): obtain HTML5 for the address.
        ScrapeResult scrape = scrapePort.scrape(address);
        Path scrapeArtifact = writeArtifact(address, "WEBSCRAPE", "listing-html",
                "scrape.html", scrape.html());

        // SIMPLIFY (US-07.1..3 slice): cleanse to action/data elements <= 100 KB.
        SimplifiedContent simplified = simplifyPort.simplify(scrape.html());
        Path simplifyArtifact = writeArtifact(address, "SIMPLIFY", "simplified-html",
                "simplified.html", simplified.html());

        // ANALYZE (US-12.4 slice): pagination detection (evidence for the flag).
        PaginationInfo pagination = paginationDetector.detect(simplified.html());

        // ANALYZE (US-13.2..4 slices): classification + working selector.
        SelectorSelection selection = selectSelector(address, simplified.html());

        // ANALYZE (US-17 slice): detect capabilities and FLAG them onto
        // the address. The AI carries the detection; generic HTML5
        // heuristics are the fallback when no AI is configured.
        CapabilityDetection capabilities = detectCapabilities(address, simplified.html());
        // ANALYZE may CORRECT the registered address (US-13.7): the
        // corrected address is what the rest of the chain works with and
        // what gets recorded with the stored selectors.
        Address flagged = address;
        if (capabilities.correctedAddress() != null) {
            flagged = address.withUrl(capabilities.correctedAddress())
                    .withCapabilities(capabilities.flags());
        } else {
            flagged = address.withCapabilities(capabilities.flags());
        }
        // Persist the detection (US-17; deferred slice-3 review
        // finding): the flags used to live only in this method's local
        // variable, so no run ever left a trace in the catalog.
        // Detections merge with the stored flags (see the use case);
        // a corrected URL is NOT persisted here — correction is
        // recorded with the stored selectors, not by rewriting the
        // registered address (US-13.7 scope).
        addressCatalog.updateCapabilities(address.id(), capabilities.flags());

        // PRINT input: selector-based extraction of the discovered items.
        List<NewsEventItem> items = new ArrayList<>(extractionPort.extract(simplified.html(),
                selection.selector(), flagged.itemKind(), flagged.id(), maxItems,
                flagged.url()));
        // US-14.1 (issue #55): the extracted items are persisted as
        // repository entities, not only as content files.
        newsEventStore.saveAll(flagged.id(), flagged.itemKind(), items);

        // Pagination-iteration block (US-12.4 slice): ONLY on addresses
        // flagged PAGINATION, and only when the AI derived a page URL
        // pattern to address the following pages with.
        int pagesScraped = 1;
        if (flagged.hasCapability(AddressCapability.PAGINATION)
                && capabilities.pageUrlPattern() != null) {
            for (int page = 1; page < maxPages; page++) {
                if (items.size() >= maxItems) {
                    break;
                }
                ScrapeResult pageScrape;
                try {
                    pageScrape = scrapePort.scrape(flagged, page);
                } catch (RuntimeException e) {
                    break; // connector cannot address this page -> end of pagination
                }
                writeArtifact(flagged, "WEBSCRAPE", "page-html",
                        "scrape-page" + (page + 1) + ".html", pageScrape.html());
                SimplifiedContent pageSimplified = simplifyPort.simplify(pageScrape.html());
                writeArtifact(flagged, "SIMPLIFY", "simplified-html",
                        "simplified-page" + (page + 1) + ".html", pageSimplified.html());
                int before = items.size();
                for (NewsEventItem item : extractionPort.extract(pageSimplified.html(),
                        selection.selector(), flagged.itemKind(), flagged.id(), maxItems,
                        flagged.url())) {
                    if (isNew(items, item)) {
                        items.add(item);
                    }
                    if (items.size() >= maxItems) {
                        break;
                    }
                }
                pagesScraped = page + 1;
                if (items.size() == before) {
                    break; // no new items -> end of the pagination
                }
            }
        }

        // FILTER block (US-17): ONLY on addresses flagged FILTERING.
        // The AI first identifies the filter-zone selector (like the
        // news/events item selectors, persisted with the address), then
        // extracts the filter definitions from within that zone.
        FilterOutcome filterOutcome = flagged.hasCapability(AddressCapability.FILTERING)
                ? extractFilters(flagged, simplified.html())
                : new FilterOutcome(List.of(), "skipped", null, null,
                "address not flagged with the filtering capability");

        // FILTER application block (US-06.6 slice, issue #73): the AI
        // chooses ONE of the extracted criteria and the SCRAPE connector
        // applies it BEFORE the filtered response is served; extraction
        // then runs on the filtered result. Only on FILTERING-flagged
        // addresses; the code encodes no site-specific filter knowledge.
        FilterApplication application = null;
        if (flagged.hasCapability(AddressCapability.FILTERING)) {
            application = applyFilter(flagged, selection, filterOutcome, filterSpec);
        }

        return new PipelineResult(flagged, scrapeArtifact.toString(),
                simplifyArtifact.toString(), scrape.sizeBytes(), simplified.sizeBytes(),
                pagination, selection.analysis(), selection.selector(),
                selection.source(), items, capabilities.flags(),
                capabilities.evidence(), capabilities.pageUrlPattern(), pagesScraped,
                capabilities.correctedAddress(),
                filterOutcome.zoneSelector(),
                filterOutcome.filters(), filterOutcome.source(), filterOutcome.note(),
                application == null ? null : application.field(),
                application == null ? null : application.value(),
                application == null ? null : application.request(),
                application == null ? List.of() : application.items(),
                application == null ? null : application.scrapeArtifact(),
                application == null ? null : application.note());
    }

    /**
     * Capability detection (US-17 + US-13.7): the AI carries the
     * detection via the capability prompt (pagination + page URL pattern,
     * filtering, and proposed corrections to the registered address);
     * generic HTML5 heuristics run as a second, independent signal. The
     * AI-proposed page URL pattern is validated against the (possibly
     * corrected) address: an inconsistent pattern is sent back to the AI
     * once for repair — the code never rewrites it with static rules.
     */
    CapabilityDetection detectCapabilities(Address address, String simplifiedHtml) {
        Set<AddressCapability> flags = new LinkedHashSet<>();
        List<String> evidence = new ArrayList<>();
        String pageUrlPattern = null;
        String correctedAddress = null;

        AiAssistantPort ai = aiPort.getIfAvailable();
        if (ai != null) {
            try {
                String answer = ai.complete(
                        AiPromptCatalog.capabilityPrompt(simplifiedHtml, address));
                String paginationSection = section(answer, "pagination");
                String filteringSection = section(answer, "filtering");
                if (paginationSection != null) {
                    if (isTrue(paginationSection, "detected")) {
                        flags.add(AddressCapability.PAGINATION);
                        pageUrlPattern = jsonField(paginationSection, "pageUrlPattern");
                        evidence.add("pagination flagged by AI"
                                + (pageUrlPattern == null ? "" : ": " + pageUrlPattern));
                    } else {
                        evidence.add("pagination not detected by AI");
                    }
                }
                if (filteringSection != null) {
                    if (isTrue(filteringSection, "detected")) {
                        flags.add(AddressCapability.FILTERING);
                        evidence.add("filtering flagged by AI");
                    } else {
                        evidence.add("filtering not detected by AI");
                    }
                }
                correctedAddress = jsonField(answer, "correctedAddress");
                if (correctedAddress != null && !correctedAddress.isBlank()
                        && !correctedAddress.equalsIgnoreCase(address.url())) {
                    evidence.add("address correction proposed by AI: "
                            + address.url() + " -> " + correctedAddress);
                } else {
                    correctedAddress = null;
                }
                // Validation feedback loop: the pattern must match the
                // (possibly corrected) registered address. One AI repair
                // round; a still-inconsistent pattern is dropped.
                String base = correctedAddress != null ? correctedAddress : address.url();
                if (pageUrlPattern != null && !patternMatches(base, pageUrlPattern)) {
                    String repaired = ai.complete(
                            AiPromptCatalog.capabilityRepairPrompt(base, pageUrlPattern));
                    String repairedPattern = jsonField(section(repaired, "pagination"),
                            "pageUrlPattern");
                    if (repairedPattern != null && patternMatches(base, repairedPattern)) {
                        evidence.add("page URL pattern repaired by AI: " + repairedPattern);
                        log.info("[AI] {}: page URL pattern repaired: {}",
                                address.id(), repairedPattern);
                        pageUrlPattern = repairedPattern;
                    } else {
                        evidence.add("page URL pattern rejected (did not match the address "
                                + base + "); iteration needs a valid pattern");
                        log.warn("[AI] {}: page URL pattern rejected - pagination "
                                + "iteration will be skipped", address.id());
                        pageUrlPattern = null;
                    }
                }
                // One verdict line, AFTER the repair loop: the reported
                // pageUrlPattern is the FINAL one (repaired or dropped).
                log.info("[AI] {}: capability detection: pagination={}, filtering={}{}",
                        address.id(),
                        paginationSection != null && isTrue(paginationSection, "detected"),
                        filteringSection != null && isTrue(filteringSection, "detected"),
                        (correctedAddress == null ? "" : ", address correction proposed")
                                + (pageUrlPattern == null ? ""
                                : ", pageUrlPattern=" + pageUrlPattern));
            } catch (RuntimeException e) {
                evidence.add("AI capability call failed: " + e.getMessage());
                log.warn("[AI] {}: AI capability call failed: {}", address.id(), e.getMessage());
            }
        } else {
            evidence.add("AI not configured — generic HTML5 heuristics used");
            log.info("[AI] {}: AI not configured - generic HTML5 heuristics only", address.id());
        }

        // The generic HTML5 heuristics run regardless of the AI answer:
        // the AI's excerpt window can miss the pager or the filter form
        // (e.g. EMA), and a false negative would silently disable the
        // gated blocks. Union of both detections, evidence says which.
        if (paginationDetector.detect(simplifiedHtml).paginated()) {
            if (flags.add(AddressCapability.PAGINATION)) {
                evidence.add("pagination flagged by URL/pager heuristic");
            }
        }
        if (exposedFilterDetector.detect(simplifiedHtml)) {
            if (flags.add(AddressCapability.FILTERING)) {
                evidence.add("filtering flagged by form/select heuristic");
            }
        }
        return new CapabilityDetection(flags, pageUrlPattern, correctedAddress,
                String.join("; ", evidence));
    }

    /**
     * Consistency of an AI-derived page URL pattern with the address it
     * must page through: same origin and the SAME path (after resolving
     * the {n} placeholder purely so the template parses) — the registered
     * address and the stored selectors must form a perfect match. A
     * different path is not silently accepted: it goes back to the AI for
     * repair (or surfaces as a proposed address correction). Pure URL
     * algebra — no site-specific knowledge.
     */
    private boolean patternMatches(String addressUrl, String pattern) {
        try {
            // {n} is a template placeholder, not a legal URI character:
            // substitute a concrete page number just for parsing.
            java.net.URI a = java.net.URI.create(addressUrl);
            java.net.URI p = java.net.URI.create(pattern.replace("{n}", "1"));
            if (a.getScheme() == null || p.getScheme() == null
                    || !a.getScheme().equalsIgnoreCase(p.getScheme())
                    || !String.valueOf(a.getHost()).equalsIgnoreCase(String.valueOf(p.getHost()))) {
                return false;
            }
            return normalizePath(a.getPath()).equals(normalizePath(p.getPath()));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private String normalizePath(String path) {
        String p = path == null || path.isBlank() ? "/" : path;
        return p.length() > 1 && p.endsWith("/") ? p.substring(0, p.length() - 1) : p;
    }

    /**
     * FILTER block (US-17.1): identifies the filter-zone selector — the
     * same identification the analysis performs for the news/events item
     * selectors: stored selector fast path (no AI), then AI
     * identification validated by probe (exactly one element) and
     * persisted merged into the stored selectors — and then extracts the
     * filter definitions from WITHIN the identified zone. Without an
     * identified zone the whole page is the scope; the generic
     * select/option parsing is the fallback when no AI is configured.
     * Called only on FILTERING-flagged addresses.
     */
    FilterOutcome extractFilters(Address address, String simplifiedHtml) {
        String zoneSelector = null;
        String zoneSource = null;
        String note = null;

        // 1. Stored filter-zone selector: fast path, AI entirely skipped.
        Optional<StoredSelectors> stored = selectorStore.load(address.id());
        if (stored.isPresent() && stored.get().filterSelector() != null
                && extractionPort.countMatches(simplifiedHtml,
                stored.get().filterSelector()) == 1) {
            zoneSelector = stored.get().filterSelector();
            zoneSource = "stored";
            log.info("[AI] {}: filter zone from stored selector - AI skipped ({})",
                    address.id(), zoneSelector);
        } else {
            // 2. AI identification, validated by probe, persisted merged.
            AiAssistantPort ai = aiPort.getIfAvailable();
            if (ai != null) {
                try {
                    String answer = ai.complete(
                            AiPromptCatalog.filterZoneSelectorPrompt(simplifiedHtml));
                    String candidate = jsonField(answer, "filterZoneSelector");
                    if (candidate != null && !candidate.isBlank()
                            && extractionPort.countMatches(simplifiedHtml, candidate) == 1) {
                        final String zone = candidate;
                        zoneSelector = candidate;
                        zoneSource = "ai";
                        log.info("[AI] {}: filter zone identified - stored: {}",
                                address.id(), zone);
                        stored.ifPresent(s -> selectorStore.save(new StoredSelectors(
                                s.addressId(), s.kind(), s.selector(), zone,
                                s.discoveredBy(), s.discoveredAt(), s.notes())));
                    } else {
                        note = "AI did not yield a valid filter-zone selector";
                        log.warn("[AI] {}: AI did not yield a valid filter-zone selector"
                                + " - whole-page scope", address.id());
                    }
                } catch (RuntimeException e) {
                    note = "AI filter-zone call failed: " + e.getMessage();
                    log.warn("[AI] {}: AI filter-zone call failed: {}",
                            address.id(), e.getMessage());
                }
            } else {
                note = "AI not configured — whole-page scope";
                log.info("[AI] {}: AI not configured - whole-page filter scope", address.id());
            }
        }

        // 1b. Stored filter CATALOGUE (owner decision 2026-11-12): when
        // the stored zone still matches AND a catalogue was captured for
        // it, the definitions are reused directly — the FILTER step
        // works without the AI.
        if (zoneSource != null && stored.isPresent()
                && !stored.get().filters().isEmpty()) {
            log.info("[AI] {}: filter catalogue from store - AI skipped ({} field(s))",
                    address.id(), stored.get().filters().size());
            return new FilterOutcome(stored.get().filters(), "stored",
                    zoneSelector, zoneSource, note);
        }

        String scopeHtml = zoneSelector != null
                ? scopeHtml(simplifiedHtml, zoneSelector)
                : simplifiedHtml;
        final String zoneSelectorF = zoneSelector;
        final String zoneSourceF = zoneSource;

        // 3. Filter definitions from within the zone. Whatever valid
        //    catalogue is extracted — by the AI or the generic parser —
        //    is persisted with the address, so later runs (and no-AI
        //    runs) reuse it.
        AiAssistantPort ai = aiPort.getIfAvailable();
        if (ai != null) {
            try {
                String answer = ai.complete(AiPromptCatalog.filtersPrompt(scopeHtml));
                List<FilterDefinition> filters = parseFilters(answer);
                if (!filters.isEmpty()) {
                    log.info("[AI] {}: {} filter field(s) extracted: {}",
                            address.id(), filters.size(),
                            filters.stream().map(FilterDefinition::name).toList());
                    persistCatalogue(address, stored, zoneSelector, filters);
                    return new FilterOutcome(filters, "ai", zoneSelectorF, zoneSourceF, note);
                }
                log.warn("[AI] {}: AI found no filter fields - generic select parsing used",
                        address.id());
                List<FilterDefinition> fallbackFilters =
                        exposedFilterDetector.parseSelects(scopeHtml);
                persistCatalogue(address, stored, zoneSelector, fallbackFilters);
                return new FilterOutcome(fallbackFilters, "fallback",
                        zoneSelectorF, zoneSourceF,
                        note == null ? "AI found no filter fields — generic select parsing used"
                                : note + "; generic select parsing used");
            } catch (RuntimeException e) {
                log.warn("[AI] {}: AI filter call failed: {}", address.id(), e.getMessage());
                List<FilterDefinition> fallbackFilters =
                        exposedFilterDetector.parseSelects(scopeHtml);
                persistCatalogue(address, stored, zoneSelector, fallbackFilters);
                return new FilterOutcome(fallbackFilters,
                        "fallback", zoneSelectorF, zoneSourceF,
                        (note == null ? "" : note + "; ") + "AI filter call failed: "
                                + e.getMessage());
            }
        }
        List<FilterDefinition> fallbackFilters =
                exposedFilterDetector.parseSelects(scopeHtml);
        persistCatalogue(address, stored, zoneSelector, fallbackFilters);
        return new FilterOutcome(fallbackFilters,
                "fallback", zoneSelectorF, zoneSourceF,
                note == null ? "AI not configured — generic select parsing used" : note);
    }

    /**
     * Persists an extracted filter catalogue merged into the stored
     * selectors (owner decision 2026-11-12): the catalogue lands in the
     * same store entry as the item selector and the filter-zone
     * selector, so any later run — with or without the AI — reuses it.
     * Never overwrites a previous non-empty catalogue with an empty
     * one; only a non-empty extraction updates the store.
     */
    private void persistCatalogue(Address address, Optional<StoredSelectors> stored,
                                  String zoneSelector, List<FilterDefinition> filters) {
        if (filters.isEmpty()) {
            return;
        }
        StoredSelectors base = stored.orElse(new StoredSelectors(address.id(),
                address.itemKind(), null, null, "fallback",
                java.time.LocalDate.now().toString(), "filter catalogue"));
        if (!filters.equals(base.filters())) {
            selectorStore.save(new StoredSelectors(base.addressId(), base.kind(),
                    base.selector(),
                    zoneSelector != null ? zoneSelector : base.filterSelector(),
                    filters, base.discoveredBy(), base.discoveredAt(), base.notes()));
            log.info("[AI] {}: filter catalogue stored ({} field(s))",
                    address.id(), filters.size());
        }
    }

    /** Outer HTML of the single element a validated selector matches. */
    private String scopeHtml(String simplifiedHtml, String zoneSelector) {
        String zone = extractionPort.zoneHtml(simplifiedHtml, zoneSelector);
        return zone != null ? zone : simplifiedHtml;
    }

    /**
     * FILTER application (US-06.6 slice, issue #73): the AI chooses ONE
     * of the extracted filter criteria and derives the request the
     * site's filter mechanism receives (query parameters or a control
     * token — no site-specific knowledge in code); the SCRAPE connector
     * applies it and the SAME validated item selector extracts the
     * narrowed listing from the filtered response. Every failure degrades
     * to an explanatory note, never to a chain failure. Called only on
     * FILTERING-flagged addresses.
     *
     * <p>When the CHAIN carries a filter spec (owner decision
     * 2026-11-12), the spec replaces the AI entirely: the criterion is
     * coherence-matched against the address's extracted catalogue and
     * the connector's servable requests, fully offline.</p>
     */
    FilterApplication applyFilter(Address address, SelectorSelection selection,
                                          FilterOutcome outcome, FilterSpec spec) {
        if (outcome.filters().isEmpty()) {
            return FilterApplication.skipped(
                    "no filter definitions extracted — nothing to apply");
        }
        // The connector's supported requests serve the AI prompt, the
        // coherence guard and the offline spec match — fetched once.
        final List<FilterRequestOption> supported =
                scrapePort.supportedFilterRequests(address);
        if (spec != null) {
            return applySpecifiedFilter(address, selection, outcome, spec, supported);
        }
        AiAssistantPort ai = aiPort.getIfAvailable();
        if (ai == null) {
            log.info("[AI] {}: AI not configured - no filter criterion can be chosen to apply",
                    address.id());
            return FilterApplication.skipped(
                    "AI not configured — no criterion can be chosen to apply");
        }
        String answer;
        try {
            answer = ai.complete(
                    AiPromptCatalog.applyFilterPrompt(outcome.filters(), address, supported));
        } catch (RuntimeException e) {
            log.warn("[AI] {}: AI filter-apply call failed: {}", address.id(), e.getMessage());
            return FilterApplication.skipped("AI filter-apply call failed: " + e.getMessage());
        }
        String field = jsonField(answer, "field");
        String value = jsonField(answer, "value");
        String request = jsonField(answer, "request");
        if (field == null || request == null || request.isBlank()) {
            log.warn("[AI] {}: AI chose no applicable filter criterion", address.id());
            return FilterApplication.skipped("AI chose no applicable filter criterion");
        }
        log.info("[AI] {}: filter criterion chosen: {} = {} (request: {})",
                address.id(), field, value, request);
        // Coherence guard: when the connector constrains what it can
        // apply, the chosen criterion must correspond to one of its
        // supported requests (matched leniently on the request text or
        // the chosen value).
        if (!supported.isEmpty()) {
            boolean coherent = supported.stream().anyMatch(o ->
                    lower(o.request()).contains(lower(request))
                            || (value != null && lower(o.criterion()).contains(lower(value))));
            if (!coherent) {
                log.warn("[AI] {}: coherence guard rejected '{} = {}' (supported: {})",
                        address.id(), field, value,
                        supported.stream().map(FilterRequestOption::criterion).toList());
                return FilterApplication.skipped("AI chose a criterion the connector "
                        + "cannot apply (supported: " + String.join("; ",
                        supported.stream().map(FilterRequestOption::criterion).toList()) + ")");
            }
        }
        return applyAndExtract(address, selection, field, value, request, "ai");
    }

    /**
     * Offline application of the CHAIN's filter spec (owner decision
     * 2026-11-12): the spec's field/value are coherence-matched against
     * the extracted catalogue and the connector's servable requests;
     * the matched request token goes to the connector. No AI is
     * consulted. A mismatch degrades to a skipped note — the chain
     * definition and the address's real filter controls must agree.
     */
    private FilterApplication applySpecifiedFilter(Address address,
                                                   SelectorSelection selection,
                                                   FilterOutcome outcome, FilterSpec spec,
                                                   List<FilterRequestOption> supported) {
        log.info("[AI] {}: filter application from the chain spec - AI bypassed: "
                + "field={} value={}", address.id(), spec.field(), spec.value());
        // The spec's field must exist in the address's filter catalogue.
        FilterDefinition definition = outcome.filters().stream()
                .filter(f -> spec.matches(f.name()))
                .findFirst()
                .orElse(null);
        if (definition == null) {
            log.warn("[AI] {}: chain filter spec '{}' does not match the address's "
                            + "filter catalogue ({}) - not applied", address.id(),
                    spec.field(),
                    outcome.filters().stream().map(FilterDefinition::name).toList());
            return FilterApplication.skipped("the chain's filter spec '"
                    + spec.field() + "' does not match the address's filter "
                    + "catalogue (fields: " + outcome.filters().stream()
                    .map(FilterDefinition::name).toList() + ")");
        }
        // The spec's value must be one the source's field actually offers.
        if (!definition.values().isEmpty()
                && definition.values().stream().noneMatch(spec::matches)) {
            log.warn("[AI] {}: chain filter spec value '{}' is not offered by field "
                    + "'{}' (values: {}) - not applied", address.id(), spec.value(),
                    definition.name(), definition.values());
            return FilterApplication.skipped("the chain's filter spec value '"
                    + spec.value() + "' is not offered by the address's filter "
                    + "field '" + definition.name() + "'");
        }
        // The request the connector can serve for the matched criterion.
        FilterRequestOption option = supported.stream()
                .filter(o -> spec.matches(o.criterion()) || spec.matches(o.request()))
                .findFirst()
                .orElse(null);
        if (option == null) {
            log.warn("[AI] {}: no servable request for the chain filter spec "
                    + "'{} = {}' (supported: {}) - not applied", address.id(),
                    spec.field(), spec.value(),
                    supported.stream().map(FilterRequestOption::criterion).toList());
            return FilterApplication.skipped("the connector does not declare a servable "
                    + "request for the chain's filter spec '" + spec.field() + " = "
                    + spec.value() + "'");
        }
        return applyAndExtract(address, selection, definition.name(), spec.value(),
                option.request(), "chain spec");
    }

    /** Shared tail of both application paths: filter the response and
     * extract with the SAME validated selector. */
    private FilterApplication applyAndExtract(Address address,
                                              SelectorSelection selection,
                                              String field, String value,
                                              String request, String source) {
        ScrapeResult filteredScrape;
        try {
            filteredScrape = scrapePort.scrapeFiltered(address, request);
        } catch (UnsupportedOperationException e) {
            return FilterApplication.skipped("criterion '" + field + " = " + value
                    + "' not applied: " + e.getMessage());
        }
        Path filteredArtifact = writeArtifact(address, "FILTER", "filtered-html",
                "scrape-filtered.html", filteredScrape.html());
        SimplifiedContent filteredSimplified = simplifyPort.simplify(filteredScrape.html());
        writeArtifact(address, "FILTER", "filtered-html",
                "simplified-filtered.html", filteredSimplified.html());
        List<NewsEventItem> filteredItems = new ArrayList<>(extractionPort.extract(
                filteredSimplified.html(), selection.selector(), address.itemKind(),
                address.id(), MAX_ITEMS, address.url()));
        return new FilterApplication(field, value, request,
                filteredArtifact.toString(), filteredItems, source, null);
    }

    /** Case-insensitive containment helper for the coherence guard. */
    private String lower(String s) {
        return s == null ? "" : s.toLowerCase();
    }

    /** Dedupe key across pages: title + link. */
    private boolean isNew(List<NewsEventItem> items, NewsEventItem candidate) {
        String key = (candidate.title() == null ? "" : candidate.title().trim())
                + "|" + (candidate.url() == null ? "" : candidate.url().trim());
        return items.stream().noneMatch(existing ->
                ((existing.title() == null ? "" : existing.title().trim())
                        + "|" + (existing.url() == null ? "" : existing.url().trim()))
                        .equals(key));
    }

    /**
     * Selector strategy: stored selector first (no AI), then AI
     * identification via the prompt catalog (persisted once validated),
     * then static heuristic fallbacks (never persisted).
     */
    SelectorSelection selectSelector(Address address, String simplifiedHtml) {
        String kind = address.itemKind();

        // 1. Stored working selector: fast path, AI entirely skipped.
        Optional<StoredSelectors> stored = selectorStore.load(address.id());
        if (stored.isPresent()) {
            StoredSelectors s = stored.get();
            if (valid(simplifiedHtml, s.selector(), kind, address)) {
                log.info("[AI] {}: stored selector valid - AI skipped ({})",
                        address.id(), s.selector());
                return new SelectorSelection(s.selector(), "stored",
                        new PageAnalysis(s.kind(), s.kind().equals("news") ? s.selector() : null,
                                s.kind().equals("event") ? s.selector() : null, false,
                                "Stored selector (discovered " + s.discoveredAt()
                                        + " by " + s.discoveredBy() + "); AI skipped. "
                                        + s.notes()));
            }
            // Stored selector went stale -> fall through to re-identification.
            log.info("[AI] {}: stored selector stale - re-identifying with the AI",
                    address.id());
        }

        // 2. Preconfigured selector (US-12.1, issue #46): configuration,
        //    not AI. Validated by the same probe; persisted with
        //    discoveredBy=preconfigured (carrying over any stored filter
        //    data) so later runs take the stored fast path.
        Optional<String> preconfigured = preconfiguredSelectors.find(address.id(), kind);
        if (preconfigured.isPresent()) {
            String configured = preconfigured.get();
            if (valid(simplifiedHtml, configured, kind, address)) {
                log.info("[AI] {}: preconfigured selector valid - AI skipped ({})",
                        address.id(), configured);
                StoredSelectors persisted = stored
                        .map(s -> new StoredSelectors(s.addressId(), kind, configured,
                                s.filterSelector(), s.filters(), "preconfigured",
                                LocalDate.now().toString(),
                                "preconfigured in neis.selectors (US-12.1)"))
                        .orElse(new StoredSelectors(address.id(), kind, configured,
                                "preconfigured", LocalDate.now().toString(),
                                "preconfigured in neis.selectors (US-12.1)"));
                selectorStore.save(persisted);
                return new SelectorSelection(configured, "preconfigured",
                        new PageAnalysis(kind,
                                kind.equals("news") ? configured : null,
                                kind.equals("event") ? configured : null, false,
                                "Preconfigured selector (neis.selectors, US-12.1); AI skipped."));
            }
            log.info("[AI] {}: preconfigured selector stale - falling to the AI",
                    address.id());
        }

        // 3. AI identification: classification prompt, then the selector
        //    prompt for the page's kind. Persisted when the probe validates it.
        AiAssistantPort ai = aiPort.getIfAvailable();
        String classification = kind;
        String aiNotes = null;
        if (ai != null) {
            try {
                String classAnswer = ai.complete(
                        AiPromptCatalog.classificationPrompt(simplifiedHtml, kind));
                String parsed = parseClassification(classAnswer);
                if (parsed != null) {
                    log.info("[AI] {}: classification: {}", address.id(), parsed);
                    classification = parsed;
                } else {
                    log.info("[AI] {}: classification answer not recognized ({}), keeping {}",
                            address.id(), snippet(classAnswer), kind);
                }
                String selectorAnswer = ai.complete(
                        kind.equals("news")
                                ? AiPromptCatalog.newsSelectorPrompt(simplifiedHtml)
                                : AiPromptCatalog.eventsSelectorPrompt(simplifiedHtml));
                String selector = jsonField(selectorAnswer, "selector");
                String reasoning = jsonField(selectorAnswer, "reasoning");
                if (selector != null && !selector.isBlank()
                        && valid(simplifiedHtml, selector, kind, address)) {
                    log.info("[AI] {}: selector identified and validated - stored for reuse: {}",
                            address.id(), selector);
                    // Same carry-over invariant as the preconfigured
                    // branch: a stale stored record's filter-zone
                    // selector and catalogue survive the re-identification
                    // (US-12.1 review finding, 2026-11-14); the record's
                    // kind is the CURRENT run's kind, not the stale one.
                    StoredSelectors persisted = stored
                            .map(s -> new StoredSelectors(s.addressId(), kind, selector,
                                    s.filterSelector(), s.filters(), "ai",
                                    LocalDate.now().toString(),
                                    reasoning == null ? "" : reasoning))
                            .orElse(new StoredSelectors(address.id(), kind,
                                    selector, "ai", LocalDate.now().toString(),
                                    reasoning == null ? "" : reasoning));
                    selectorStore.save(persisted);
                    return new SelectorSelection(selector, "ai",
                            new PageAnalysis(classification,
                                    kind.equals("news") ? selector : null,
                                    kind.equals("event") ? selector : null,
                                    true, reasoning == null ? "" : reasoning));
                }
                aiNotes = "AI did not yield a valid selector"
                        + (reasoning == null || reasoning.isBlank() ? "" : ": " + reasoning);
                log.warn("[AI] {}: AI selector did not validate - heuristic fallback used",
                        address.id());
            } catch (RuntimeException e) {
                aiNotes = "AI call failed: " + e.getMessage();
                log.warn("[AI] {}: AI call failed: {}", address.id(), e.getMessage());
            }
        } else {
            aiNotes = "AI integration point not configured (neis.ai.enabled / MELIOUS_API_KEY)";
            log.info("[AI] {}: AI not configured - heuristic fallback selectors", address.id());
        }

        // 4. Static heuristic fallback (not persisted — the point is to
        //    store AI-identified working selectors only).
        String selected = null;
        for (String candidate : fallbackSelectors(address)) {
            if (valid(simplifiedHtml, candidate, kind, address)) {
                selected = candidate;
                break;
            }
        }
        if (selected == null) {
            selected = fallbackSelectors(address).getLast();
        }
        return new SelectorSelection(selected, "fallback",
                PageAnalysis.fallback(kind,
                        (aiNotes == null ? "AI did not yield a valid selector" : aiNotes)
                                + " — heuristic selector used"));
    }

    private boolean valid(String simplifiedHtml, String selector, String kind,
                          Address address) {
        return selector != null && !selector.isBlank()
                && !extractionPort.extract(simplifiedHtml, selector, kind,
                        address.id(), 1, address.url()).isEmpty();
    }

    private List<String> fallbackSelectors(Address address) {
        return address.type() == AddressType.URL_NEWS
                ? FALLBACK_NEWS_SELECTORS : FALLBACK_EVENT_SELECTORS;
    }

    /** Parses the single-word classification answer; null when unusable. */
    private String parseClassification(String answer) {
        if (answer == null) {
            return null;
        }
        String word = answer.trim().toLowerCase();
        if (word.startsWith("news")) {
            return "news";
        }
        if (word.startsWith("event")) {
            return "event";
        }
        if (word.startsWith("other")) {
            return "other";
        }
        return null;
    }

    /** Up to the first 60 characters of an AI answer (whole answer,
     * whitespace-flattened), for the trace logs — never the full
     * answer body. */
    private String snippet(String answer) {
        if (answer == null || answer.isBlank()) {
            return "(empty)";
        }
        String oneLine = answer.trim().replaceAll("\\s+", " ");
        return oneLine.length() <= 60 ? oneLine : oneLine.substring(0, 60) + "...";
    }

    private String jsonField(String json, String field) {
        if (json == null) {
            return null;
        }
        Matcher m = Pattern.compile("\"" + field + "\"\\s*:\\s*(null|\"([^\"]*)\")")
                .matcher(json);
        if (!m.find() || m.group(1).equals("null")) {
            return null;
        }
        return m.group(2);
    }

    /** Flat JSON object section of one key: `"key":{ ... }` -> contents. */
    private String section(String json, String key) {
        if (json == null) {
            return null;
        }
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*\\{").matcher(json);
        if (!m.find()) {
            return null;
        }
        // Brace-aware scan: string values may contain braces ({n} page
        // placeholders in URL patterns).
        int start = m.end();
        int depth = 1;
        for (int i = start; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return json.substring(start, i);
                }
            }
        }
        return null;
    }

    private boolean isTrue(String section, String field) {
        if (section == null) {
            return false;
        }
        Matcher m = Pattern.compile("\"" + field + "\"\\s*:\\s*(true|false)")
                .matcher(section);
        return m.find() && m.group(1).equals("true");
    }

    /**
     * Lenient parse of the filters prompt answer:
     * {@code {"filters":[{"name":..,"values":[..]},..],"reasoning":..}}.
     */
    private List<FilterDefinition> parseFilters(String answer) {
        List<FilterDefinition> filters = new ArrayList<>();
        if (answer == null) {
            return filters;
        }
        int arrayStart = answer.indexOf("\"filters\"");
        if (arrayStart < 0) {
            return filters;
        }
        arrayStart = answer.indexOf('[', arrayStart);
        if (arrayStart < 0) {
            return filters;
        }
        // Scan top-level objects of the array by brace depth.
        int depth = 0;
        int objectStart = -1;
        for (int i = arrayStart + 1; i < answer.length(); i++) {
            char c = answer.charAt(i);
            if (c == '{') {
                if (depth == 0) {
                    objectStart = i;
                }
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0 && objectStart >= 0) {
                    String object = answer.substring(objectStart, i + 1);
                    String name = jsonField(object, "name");
                    List<String> values = arrayField(object, "values");
                    if (name != null) {
                        filters.add(new FilterDefinition(name, values));
                    }
                    objectStart = -1;
                }
            } else if (c == ']' && depth == 0) {
                break; // end of the filters array
            }
        }
        return filters;
    }

    /** Parses a flat string array: `["a","b"]` -> [a, b]. */
    private List<String> arrayField(String json, String field) {
        List<String> values = new ArrayList<>();
        Matcher m = Pattern.compile("\"" + field + "\"\\s*:\\s*\\[(.*?)\\]",
                Pattern.DOTALL).matcher(json);
        if (!m.find()) {
            return values;
        }
        for (String raw : m.group(1).split("\",\\s*\"")) {
            String value = raw.replaceFirst("^\"+", "").replaceFirst("\"+$", "")
                    .replace("\\\"", "\"").trim();
            if (!value.isEmpty()) {
                values.add(value);
            }
        }
        return values;
    }

    /**
     * Writes one chain artifact through the content store (US-11):
     * the deterministic capture folder of the address's listing page,
     * under the task's own content folder (US-11.3).
     */
    Path writeArtifact(Address address, String taskCode, String kind,
                       String fileName, String content) {
        return contentStore.writeTaskOutput(address, address.url(), taskCode,
                kind, fileName, content);
    }

    /** Chosen selector plus its provenance and the page analysis. */
    record SelectorSelection(String selector, String source, PageAnalysis analysis) {
    }

    /** Detected capabilities flagged onto the address (US-17), plus the
     * AI-proposed correction of the registered address (US-13.7). */
    record CapabilityDetection(Set<AddressCapability> flags, String pageUrlPattern,
                               String correctedAddress, String evidence) {
    }

    /** FILTER block outcome: the identified zone selector, definitions
     * extracted from within it, and provenance (US-17.1). */
    record FilterOutcome(List<FilterDefinition> filters, String source,
                         String zoneSelector, String zoneSource, String note) {
    }

    /** FILTER application outcome (US-06.6 slice): the applied
     * criterion, the filtered-response scrape and the narrowed
     * extraction; {@code skipped} with a note when no criterion could
     * be applied. */
    record FilterApplication(String field, String value, String request,
                             String scrapeArtifact, List<NewsEventItem> items,
                             String source, String note) {
        static FilterApplication skipped(String note) {
            return new FilterApplication(null, null, null, null, List.of(),
                    "skipped", note);
        }
    }
}
