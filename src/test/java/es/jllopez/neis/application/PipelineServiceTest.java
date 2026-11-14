package es.jllopez.neis.application;

import es.jllopez.neis.domain.model.Address;
import es.jllopez.neis.domain.model.AddressCapability;
import es.jllopez.neis.domain.model.AddressType;
import es.jllopez.neis.domain.model.FilterDefinition;
import es.jllopez.neis.domain.model.FilterRequestOption;
import es.jllopez.neis.domain.model.FilterSpec;
import es.jllopez.neis.domain.model.ScrapeResult;
import es.jllopez.neis.domain.model.StoredSelectors;
import es.jllopez.neis.domain.port.AiAssistantPort;
import es.jllopez.neis.domain.port.ContentScrapePort;
import es.jllopez.neis.domain.port.SelectorExtractionPort;
import es.jllopez.neis.domain.port.SelectorStorePort;
import es.jllopez.neis.domain.port.SimplifyPort;
import es.jllopez.neis.domain.service.ExposedFilterDetector;
import es.jllopez.neis.domain.service.PaginationDetector;
import es.jllopez.neis.infrastructure.adapter.scrape.JsoupSelectorConnector;
import es.jllopez.neis.infrastructure.adapter.scrape.JsoupSimplifyConnector;
import es.jllopez.neis.testsupport.InMemoryAddressCatalogPort;
import es.jllopez.neis.testsupport.InMemoryContentStorePort;
import es.jllopez.neis.testsupport.SeedAddresses;
import es.jllopez.neis.domain.service.UrlSyntaxValidator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The predefined static MVP chain (issue #90): SCRAPE - SIMPLIFY -
 * ANALYZE - PRINT, over the real jsoup connectors and the real
 * Playwright-rendered fixtures. Covers the selector strategy: stored
 * selector fast path (no AI), AI identification with persistence, and
 * static heuristic fallback.
 */
class PipelineServiceTest {

    @TempDir
    Path workDir;

    /** Catalog service over the in-memory test port, seeded with the
     *  issue #62 example addresses (the production catalog is the
     *  database-backed connector, exercised by the persistence tests). */
    private final AddressCatalogService catalog = new AddressCatalogService(
            new InMemoryAddressCatalogPort(SeedAddresses.verified()),
            new UrlSyntaxValidator());
    private final SimplifyPort simplifyPort = new JsoupSimplifyConnector();
    private final SelectorExtractionPort extractionPort = new JsoupSelectorConnector();
    private final PaginationDetector paginationDetector = new PaginationDetector();

    /** In-memory selector store (the FileSelectorStoreConnector is
     *  exercised by the real CLI run). */
    private final Map<String, StoredSelectors> store = new HashMap<>();
    private final SelectorStorePort selectorStore = new SelectorStorePort() {
        @Override
        public Optional<StoredSelectors> load(String addressId) {
            return Optional.ofNullable(store.get(addressId));
        }

        @Override
        public void save(StoredSelectors selectors) {
            store.put(selectors.addressId(), selectors);
        }
    };

    private final ContentScrapePort scrapePort = new ContentScrapePort() {
        private String fixture(Address address, int pageIndex) {
            String key = pageIndex == 0 ? address.id() : address.id() + "#p" + pageIndex;
            return "/mvp/samples/" + switch (key) {
                case "ADDR-001" -> "addr-001-news.html";
                case "ADDR-002" -> "addr-002-events.html";
                case "ADDR-003" -> "addr-003-news-spa-rendered.html";
                case "ADDR-004" -> "addr-004-events-spa-rendered.html";
                case "ADDR-005" -> "addr-005-events-rendered.html";
                case "ADDR-005#p1" -> "addr-005-events-page2-rendered.html";
                case "ADDR-006" -> "addr-006-news-rendered.html";
                case "ADDR-007" -> "addr-007-news-rendered.html";
                case "ADDR-007#p1" -> "addr-007-news-page2-rendered.html";
                case "ADDR-007#p2" -> "addr-007-news-page3-rendered.html";
                case "ADDR-007#p3" -> "addr-007-news-page4-rendered.html";
                case "ADDR-007#p4" -> "addr-007-news-page5-rendered.html";
                case "ADDR-008" -> "addr-008-events-rendered.html";
                case "ADDR-008#p1" -> "addr-008-events-page2-rendered.html";
                case "ADDR-008#p2" -> "addr-008-events-page3-rendered.html";
                case "ADDR-008#p3" -> "addr-008-events-page4-rendered.html";
                case "ADDR-008#p4" -> "addr-008-events-page5-rendered.html";
                case "ADDR-009" -> "addr-009-news-rendered.html";
                case "ADDR-009#p1" -> "addr-009-news-page2-rendered.html";
                case "ADDR-009#p2" -> "addr-009-news-page3-rendered.html";
                case "ADDR-009#p3" -> "addr-009-news-page4-rendered.html";
                case "ADDR-009#p4" -> "addr-009-news-page5-rendered.html";
                default -> throw new IllegalStateException("unexpected " + key);
            };
        }

        @Override
        public ScrapeResult scrape(Address address) {
            return scrape(address, 0);
        }

        @Override
        public ScrapeResult scrape(Address address, int pageIndex) {
            try (InputStream in = getClass().getResourceAsStream(fixture(address, pageIndex))) {
                String html = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                return new ScrapeResult(html, "text/html; charset=UTF-8", html.length());
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
    };

    @SuppressWarnings("unchecked")
    private ObjectProvider<AiAssistantPort> aiProvider(AiAssistantPort port) {
        ObjectProvider<AiAssistantPort> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(port);
        return provider;
    }

    private ContentStoreService contentStore() {
        return new ContentStoreService(new InMemoryContentStorePort(), workDir.toString());
    }

    /** Empty preconfigured-selector catalog (US-12.1): no configured
     * selectors unless a test passes its own. */
    private PreconfiguredSelectors preconfigured() {
        return new PreconfiguredSelectors(new PreconfiguredSelectorProperties(List.of()));
    }

    /** Preconfigured-selector catalog with the given entries (US-12.1). */
    private PreconfiguredSelectors preconfigured(
            List<PreconfiguredSelectorProperties.Entry> entries) {
        return new PreconfiguredSelectors(new PreconfiguredSelectorProperties(entries));
    }

    /** The deterministic capture folder of an address's listing page
     * (US-11.4): capture ids derive from the URL, so a fresh service
     * computes the same folder the run wrote to. */
    private Path captureDirOf(Address address) {
        return workDir.resolve(address.id())
                .resolve(contentStore().captureId(address.url()));
    }

    private PipelineService service(ObjectProvider<AiAssistantPort> ai) {
        return new PipelineService(scrapePort, simplifyPort, extractionPort,
                selectorStore, paginationDetector, new ExposedFilterDetector(),
                ai, catalog, new UrlSyntaxValidator(), preconfigured(), contentStore(), workDir.toString());
    }

    @Test
    void chainRefusesAnAddressWithAMalformedUrl() {
        Address bad = new Address("ADDR-010", "Bad URL", "https://exa mple.eu/news",
                AddressType.URL_NEWS);
        AddressCatalogService badCatalog = new AddressCatalogService(
                new InMemoryAddressCatalogPort(List.of(bad)), new UrlSyntaxValidator());
        PipelineService chain = new PipelineService(scrapePort, simplifyPort,
                extractionPort, selectorStore, paginationDetector,
                new ExposedFilterDetector(), aiProvider(aiAnswering("news",
                        "{\"selector\":\"ul li\",\"reasoning\":\"\"}")),
                badCatalog, new UrlSyntaxValidator(), preconfigured(), contentStore(), workDir.toString());

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> chain.run(bad));
        assertTrue(e.getMessage().contains("ADDR-010"));
        assertTrue(e.getMessage().contains("invalid"));
    }

    @Test
    void chainRefusesAnUnverifiedAddress() {
        // Freshly registered addresses start UNVERIFIED (US-04.2).
        Address unverified = new Address("ADDR-011", "Unverified",
                "https://example.eu/news", AddressType.URL_NEWS);
        AddressCatalogService catalog2 = new AddressCatalogService(
                new InMemoryAddressCatalogPort(List.of(unverified)), new UrlSyntaxValidator());
        PipelineService chain = new PipelineService(scrapePort, simplifyPort,
                extractionPort, selectorStore, paginationDetector,
                new ExposedFilterDetector(), aiProvider(aiAnswering("news",
                        "{\"selector\":\"ul li\",\"reasoning\":\"\"}")),
                catalog2, new UrlSyntaxValidator(), preconfigured(), contentStore(), workDir.toString());

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> chain.run(unverified));
        assertTrue(e.getMessage().contains("not eligible"));
        assertTrue(e.getMessage().contains("UNVERIFIED"));
        assertTrue(e.getMessage().contains("address verify"));
    }

    @Test
    void theChainPersistsTheDetectedCapabilitiesOntoTheAddress() {
        // Deferred slice-3 review finding: the detection used to live
        // only in the run's local `flagged` variable, so no run ever
        // left the flags in the catalog.
        Address good = SeedAddresses.verified().get(0);
        PipelineService chain = new PipelineService(scrapePort, simplifyPort,
                extractionPort, selectorStore, paginationDetector,
                new ExposedFilterDetector(),
                aiProvider(aiAnswering("news", "{\"selector\":\"ul li\",\"reasoning\":\"\"}",
                        "{\"pagination\":{\"detected\":true,\"pageUrlPattern\":null,"
                                + "\"reasoning\":\"\"},"
                                + "\"filtering\":{\"detected\":true,\"reasoning\":\"\"},"
                                + "\"correctedAddress\":null}",
                        "{\"filters\":[],\"reasoning\":\"none\"}")),
                catalog, new UrlSyntaxValidator(), preconfigured(), contentStore(), workDir.toString());

        chain.run(good);

        Address stored = catalog.byId(good.id());
        assertTrue(stored.hasCapability(AddressCapability.FILTERING),
                "the FILTERING detection must be persisted onto the address");
        assertTrue(stored.hasCapability(AddressCapability.PAGINATION),
                "the PAGINATION detection must be persisted onto the address");
        assertEquals(good.verificationStatus(), stored.verificationStatus(),
                "capability persistence must not touch the verification state");
    }

    /** AI stub answering the task-specific prompt catalog (issue #90). */
    private AiAssistantPort aiAnswering(String classification, String selectorJson) {
        return aiAnswering(classification, selectorJson,
                "{\"pagination\":{\"detected\":false,\"pageUrlPattern\":null,"
                        + "\"reasoning\":\"\"},"
                        + "\"filtering\":{\"detected\":false,\"reasoning\":\"\"},"
                        + "\"correctedAddress\":null}",
                "{\"filters\":[],\"reasoning\":\"none\"}");
    }

    /** AI stub with explicit capability-detection and filter answers (US-17). */
    private AiAssistantPort aiAnswering(String classification, String selectorJson,
                                        String capabilityJson, String filtersJson) {
        return aiAnswering(classification, selectorJson, capabilityJson, filtersJson,
                "{\"field\":null,\"value\":null,\"request\":null,\"reasoning\":\"stub default\"}");
    }

    /** AI stub with an explicit filter-application answer (US-06.6). */
    private AiAssistantPort aiAnswering(String classification, String selectorJson,
                                        String capabilityJson, String filtersJson,
                                        String applyFilterJson) {
        return prompt -> {
            if (prompt.contains("Classify the page")) {
                return classification;
            }
            if (prompt.contains("CAPABILITIES")) {
                return capabilityJson;
            }
            if (prompt.contains("FILTER ZONE")) {
                return "{\"filterZoneSelector\":null,\"reasoning\":\"stub default\"}";
            }
            if (prompt.contains("applying ONE filter")) {
                return applyFilterJson;
            }
            if (prompt.contains("FILTER controls")) {
                return filtersJson;
            }
            return selectorJson;
        };
    }

    /**
     * AI stub whose capability answer depends on the prompt: the first
     * capability call answers {@code first}, the repair round answers
     * {@code second} (US-13.7 validation feedback loop).
     */
    private AiAssistantPort aiCapabilityRepair(String first, String second) {
        java.util.concurrent.atomic.AtomicInteger capabilityCalls =
                new java.util.concurrent.atomic.AtomicInteger();
        return prompt -> {
            if (prompt.contains("Classify the page")) {
                return "event";
            }
            if (prompt.contains("CAPABILITIES") || prompt.contains("pageUrlPattern")) {
                return capabilityCalls.getAndIncrement() == 0 ? first : second;
            }
            if (prompt.contains("FILTER controls")) {
                return "{\"filters\":[],\"reasoning\":\"none\"}";
            }
            return "{\"selector\":\"article.ema-event--teaser\",\"reasoning\":\"\"}";
        };
    }

    @Test
    void chainIdentifiesSelectorWithAiAndPersistsIt() {
        AiAssistantPort ai = aiAnswering("news",
                "{\"selector\":\"sedia-result-card-news-translation\",\"reasoning\":\"repeated news result cards\"}");
        PipelineResult result = service(aiProvider(ai)).run(catalog.byId("ADDR-003"));

        assertEquals("ai", result.selectorSource());
        assertTrue(result.analysis().aiAssisted(), "classification must come from the AI");
        assertEquals("news", result.analysis().classification());
        assertEquals("sedia-result-card-news-translation", result.selectedSelector());
        assertTrue(result.pagination().paginated(), "rendered fixture has ?pageNumber= pagination");
        assertFalse(result.items().isEmpty(), "extraction must yield items");
        assertEquals("news", result.items().get(0).kind());
        assertTrue(captureDirOf(catalog.byId("ADDR-003"))
                .resolve("content").resolve("WEBSCRAPE").resolve("scrape.html")
                .toFile().exists());
        assertTrue(captureDirOf(catalog.byId("ADDR-003"))
                .resolve("content").resolve("SIMPLIFY").resolve("simplified.html")
                .toFile().exists());

        StoredSelectors saved = store.get("ADDR-003");
        assertTrue(saved != null && saved.selector().equals("sedia-result-card-news-translation"),
                "validated AI selector must be stored with the source");
        assertEquals("ai", saved.discoveredBy());
    }

    @Test
    void storedSelectorSkipsTheAiEntirely() {
        store.put("ADDR-003", new StoredSelectors("ADDR-003", "news",
                "sedia-result-card-news-translation", "ai", "2026-11-11",
                "repeated news result cards"));
        // The selector-identification AI must not be reached; the
        // capability-detection and filter-zone blocks still use their own
        // AI prompts (US-17).
        AiAssistantPort selectorAiMustNotCall = prompt -> {
            if (prompt.contains("REPEATING list items")) {
                throw new AssertionError(
                        "selector AI must be skipped when a stored selector works");
            }
            if (prompt.contains("CAPABILITIES")) {
                return "{\"pagination\":{\"detected\":false,\"pageUrlPattern\":null,"
                        + "\"reasoning\":\"\"},"
                        + "\"filtering\":{\"detected\":false,\"reasoning\":\"\"}}";
            }
            return "news";
        };

        PipelineResult result = service(aiProvider(selectorAiMustNotCall)).run(catalog.byId("ADDR-003"));

        assertEquals("stored", result.selectorSource());
        assertFalse(result.analysis().aiAssisted());
        assertFalse(result.items().isEmpty(), "stored selector must still extract items");
        assertTrue(result.analysis().notes().contains("AI skipped"));
    }

    @Test
    void preconfiguredSelectorSkipsTheAi() {
        // US-12.1: the configuration's selector for ADDR-003 is used
        // directly — the selector-identification AI is never reached.
        AiAssistantPort selectorAiMustNotCall = prompt -> {
            if (prompt.contains("REPEATING list items")) {
                throw new AssertionError(
                        "selector AI must be skipped when a preconfigured selector works");
            }
            if (prompt.contains("CAPABILITIES")) {
                return "{\"pagination\":{\"detected\":false,\"pageUrlPattern\":null,"
                        + "\"reasoning\":\"\"},"
                        + "\"filtering\":{\"detected\":false,\"reasoning\":\"\"}}";
            }
            return "news";
        };
        PreconfiguredSelectors configured = preconfigured(List.of(
                new PreconfiguredSelectorProperties.Entry("ADDR-003", "news",
                        "sedia-result-card-news-translation")));
        PipelineService service = new PipelineService(scrapePort, simplifyPort,
                extractionPort, selectorStore, paginationDetector,
                new ExposedFilterDetector(), aiProvider(selectorAiMustNotCall),
                catalog, new UrlSyntaxValidator(), configured, contentStore(),
                workDir.toString());

        PipelineResult result = service.run(catalog.byId("ADDR-003"));

        assertEquals("preconfigured", result.selectorSource());
        assertFalse(result.analysis().aiAssisted());
        assertFalse(result.items().isEmpty(), "preconfigured selector must extract items");
        assertEquals("preconfigured", store.get("ADDR-003").discoveredBy(),
                "a validated preconfigured selector is persisted");
        assertEquals("sedia-result-card-news-translation", store.get("ADDR-003").selector());
    }

    @Test
    void staleStoredSelectorFallsToThePreconfiguredOneBeforeTheAi() {
        store.put("ADDR-003", new StoredSelectors("ADDR-003", "news",
                ".does-not-match-anymore", "ai", "2026-11-01", "stale"));
        AiAssistantPort selectorAiMustNotCall = prompt -> {
            if (prompt.contains("REPEATING list items")) {
                throw new AssertionError(
                        "a valid preconfigured selector must win over the AI");
            }
            if (prompt.contains("CAPABILITIES")) {
                return "{\"pagination\":{\"detected\":false,\"pageUrlPattern\":null,"
                        + "\"reasoning\":\"\"},"
                        + "\"filtering\":{\"detected\":false,\"reasoning\":\"\"}}";
            }
            return "news";
        };
        PreconfiguredSelectors configured = preconfigured(List.of(
                new PreconfiguredSelectorProperties.Entry("ADDR-003", "news",
                        "sedia-result-card-news-translation")));
        PipelineService service = new PipelineService(scrapePort, simplifyPort,
                extractionPort, selectorStore, paginationDetector,
                new ExposedFilterDetector(), aiProvider(selectorAiMustNotCall),
                catalog, new UrlSyntaxValidator(), configured, contentStore(),
                workDir.toString());

        PipelineResult result = service.run(catalog.byId("ADDR-003"));

        assertEquals("preconfigured", result.selectorSource());
        assertFalse(result.items().isEmpty());
        // The stale stored record's identity carries into the persisted
        // replacement (same address, same kind).
        assertEquals("preconfigured", store.get("ADDR-003").discoveredBy());
    }

    @Test
    void staleStoredSelectorTriggersReIdentification() {
        store.put("ADDR-003", new StoredSelectors("ADDR-003", "news",
                ".does-not-match-anymore", "ai", "2026-11-01", "stale"));
        AiAssistantPort ai = aiAnswering("news",
                "{\"selector\":\"sedia-result-card-news-translation\",\"reasoning\":\"refreshed\"}");

        PipelineResult result = service(aiProvider(ai)).run(catalog.byId("ADDR-003"));

        assertEquals("ai", result.selectorSource(), "stale selector must trigger AI re-run");
        assertEquals("sedia-result-card-news-translation",
                store.get("ADDR-003").selector(), "store must be refreshed");
    }

    @Test
    void chainExtractsRealRenderedNewsFromAddr001() {
        PipelineResult result = service(aiProvider(aiAnswering("news",
                "{\"selector\":\"li.js-news-item\",\"reasoning\":\"\"}")))
                .run(catalog.byId("ADDR-001"));
        assertEquals("ai", result.selectorSource(), "li.js-news-item is the real ADDR-001 selector");
        assertTrue(result.items().size() >= 3, "real news list must yield several items");
        assertTrue(result.items().get(0).title() != null && !result.items().get(0).title().isBlank());
    }

    @Test
    void chainFallsBackWhenAiNotConfigured() {
        @SuppressWarnings("unchecked")
        ObjectProvider<AiAssistantPort> empty = mock(ObjectProvider.class);
        when(empty.getIfAvailable()).thenReturn(null);

        PipelineResult result = service(empty).run(catalog.byId("ADDR-003"));

        assertEquals("fallback", result.selectorSource());
        assertFalse(result.analysis().aiAssisted(), "no AI configured -> fallback analysis");
        assertEquals("news", result.analysis().classification());
        assertTrue(result.analysis().notes().contains("not configured"));
        assertFalse(result.items().isEmpty(), "heuristic fallback selector must still extract");
        assertEquals("news", result.items().get(0).kind());
        // The fallback SELECTOR is never persisted; the filter catalogue
        // IS (owner decision 2026-11-12: the no-AI path also builds the
        // catalogue), so the entry carries the catalogue but a null
        // selector.
        if (store.containsKey("ADDR-003")) {
            assertNull(store.get("ADDR-003").selector(),
                    "the fallback selector itself is never persisted");
            assertFalse(store.get("ADDR-003").filters().isEmpty(),
                    "the generic parser's filter catalogue is persisted for offline use");
        }
    }

    @Test
    void chainFallsBackWhenAiFails() {
        AiAssistantPort failing = prompt -> {
            throw new IllegalStateException("provider unreachable");
        };
        PipelineResult result = service(aiProvider(failing)).run(catalog.byId("ADDR-003"));
        assertEquals("fallback", result.selectorSource());
        assertTrue(result.analysis().notes().startsWith("AI call failed"));
        assertFalse(result.items().isEmpty());
    }

    @Test
    void aRunCoversOneAddressEndToEnd() {
        PipelineResult result = service(aiProvider(aiAnswering("other",
                "{\"selector\":null,\"reasoning\":\"none\"}")))
                .run(catalog.byId("ADDR-003"));
        assertEquals("fallback", result.selectorSource());
        assertTrue(captureDirOf(result.address())
                .resolve("content").resolve("SIMPLIFY").resolve("simplified.html")
                .toFile().exists());
    }

    @Test
    void chainFlagsCapabilitiesAndIteratesPaginationForHaDeaEvents() {
        AiAssistantPort ai = aiAnswering("event",
                "{\"selector\":\"article.ecl-content-item\",\"reasoning\":\"repeating content items\"}",
                "{\"pagination\":{\"detected\":true,"
                        + "\"pageUrlPattern\":\"https://hadea.ec.europa.eu/events_en?page={n}\","
                        + "\"reasoning\":\"pager links\"},"
                        + "\"filtering\":{\"detected\":true,\"reasoning\":\"facet form\"}}",
                "{\"filters\":[{\"name\":\"Event status\",\"values\":"
                        + "[\"Upcoming and ongoing\",\"Past\"]}],\"reasoning\":\"\"}");

        PipelineResult result = service(aiProvider(ai)).run(catalog.byId("ADDR-005"));

        assertTrue(result.address().hasCapability(AddressCapability.PAGINATION),
                "AI flagged pagination must be set on the address");
        assertTrue(result.address().hasCapability(AddressCapability.FILTERING),
                "AI flagged filtering must be set on the address");
        assertEquals("https://hadea.ec.europa.eu/events_en?page={n}",
                result.pageUrlPattern());
        assertEquals("ai", result.selectorSource());
        assertEquals(13, result.items().size(),
                "pagination iteration must collect all events of both pages");
        assertEquals(2, result.pagesScraped());
        assertEquals(1, result.filters().size());
        assertEquals("ai", result.filterSource());
        assertEquals("Event status", result.filters().get(0).name());
    }

    @Test
    void filterBlockIsSkippedWhenAddressNotFlagged() {
        // default aiAnswering: AI reports no capabilities -> nothing flagged
        PipelineResult result = service(aiProvider(aiAnswering("event",
                "{\"selector\":\"div.exc-event-item\",\"reasoning\":\"\"}")))
                .run(catalog.byId("ADDR-002"));

        assertTrue(result.capabilities().isEmpty());
        assertEquals("skipped", result.filterSource(),
                "FILTER block must not run on an address without the filtering flag");
        assertTrue(result.filters().isEmpty());
    }

    @Test
    void heuristicsFlagCapabilitiesWhenNoAiConfigured() {
        @SuppressWarnings("unchecked")
        ObjectProvider<AiAssistantPort> empty = mock(ObjectProvider.class);
        when(empty.getIfAvailable()).thenReturn(null);

        PipelineResult result = service(empty).run(catalog.byId("ADDR-005"));

        assertTrue(result.address().hasCapability(AddressCapability.PAGINATION),
                "generic pager heuristic must flag pagination (simplified page keeps the pager nav)");
        assertTrue(result.address().hasCapability(AddressCapability.FILTERING),
                "generic form/select heuristic must flag filtering");
        assertEquals("fallback", result.filterSource());
        assertFalse(result.filters().isEmpty(),
                "generic select parsing must extract filter fields");
        assertTrue(result.pageUrlPattern() == null,
                "no AI -> no page URL pattern -> iteration cannot run");
        assertEquals(1, result.pagesScraped(),
                "without the page pattern no additional page may be scraped");
        assertTrue(result.items().size() < 13,
                "page 1 only: the 3 events of page 2 must be missing");
    }

    @Test
    void haDeaNewsIsCappedAtMaxItems() {
        AiAssistantPort ai = aiAnswering("news",
                "{\"selector\":\"article.ecl-content-item\",\"reasoning\":\"repeating content items\"}",
                "{\"pagination\":{\"detected\":true,"
                        + "\"pageUrlPattern\":\"https://hadea.ec.europa.eu/news_en?page={n}\","
                        + "\"reasoning\":\"pager links\"},"
                        + "\"filtering\":{\"detected\":true,\"reasoning\":\"facet form\"}}",
                "{\"filters\":[],\"reasoning\":\"\"}");

        PipelineResult result = service(aiProvider(ai)).run(catalog.byId("ADDR-006"));

        assertTrue(result.items().size() <= 50, "MAX_ITEMS cap must hold");
        assertFalse(result.items().isEmpty());
        assertTrue(result.address().hasCapability(AddressCapability.FILTERING));
    }

    @Test
    void emaUpcomingEventsIteratePagesUpToTheCap() {
        AiAssistantPort ai = aiAnswering("event",
                "{\"selector\":\"article.ema-event--teaser\",\"reasoning\":\"repeating event teasers\"}",
                "{\"pagination\":{\"detected\":true,"
                        + "\"pageUrlPattern\":\"https://www.ema.europa.eu/en/events/upcoming-events?page={n}\","
                        + "\"reasoning\":\"pager links\"},"
                        + "\"filtering\":{\"detected\":true,\"reasoning\":\"filter form\"}}",
                "{\"filters\":[],\"reasoning\":\"\"}");

        PipelineResult result = service(aiProvider(ai)).run(catalog.byId("ADDR-008"));

        assertTrue(result.address().hasCapability(AddressCapability.PAGINATION));
        assertTrue(result.address().hasCapability(AddressCapability.FILTERING));
        assertEquals("ai", result.selectorSource());
        assertEquals(50, result.items().size(),
                "10 events/page over 5 fixture pages must reach the MAX_ITEMS cap");
        assertEquals(5, result.pagesScraped());
    }

    @Test
    void emaNewsIteratesPagesUpToTheCap() {
        AiAssistantPort ai = aiAnswering("news",
                "{\"selector\":\"article.ema-news--teaser\",\"reasoning\":\"repeating news teasers\"}",
                "{\"pagination\":{\"detected\":true,"
                        + "\"pageUrlPattern\":\"https://www.ema.europa.eu/en/news?page={n}\","
                        + "\"reasoning\":\"pager links\"},"
                        + "\"filtering\":{\"detected\":true,\"reasoning\":\"keyword filter\"}}",
                "{\"filters\":[],\"reasoning\":\"\"}");

        PipelineResult result = service(aiProvider(ai)).run(catalog.byId("ADDR-007"));

        assertTrue(result.address().hasCapability(AddressCapability.PAGINATION));
        assertTrue(result.address().hasCapability(AddressCapability.FILTERING));
        assertEquals(50, result.items().size(),
                "10 news/page over 5 fixture pages must reach the MAX_ITEMS cap");
        assertEquals(5, result.pagesScraped());
    }

    @Test
    void analysisProposesAndAppliesAnAddressCorrection() {
        // Registered under the parent path; the AI recognizes the actual
        // listing lives under /en/events/upcoming-events and corrects it.
        Address looselyRegistered = new Address("ADDR-008", "EMA events",
                "https://www.ema.europa.eu/en/events", AddressType.URL_EVENTS)
                .withVerification(es.jllopez.neis.domain.model.VerificationStatus.VERIFIED,
                        "2026-11-11T00:00:00Z");
        AiAssistantPort ai = aiAnswering("event",
                "{\"selector\":\"article.ema-event--teaser\",\"reasoning\":\"\"}",
                "{\"pagination\":{\"detected\":true,"
                        + "\"pageUrlPattern\":\"https://www.ema.europa.eu/en/events/upcoming-events?page={n}\","
                        + "\"reasoning\":\"pager links\"},"
                        + "\"filtering\":{\"detected\":true,\"reasoning\":\"filter form\"},"
                        + "\"correctedAddress\":\"https://www.ema.europa.eu/en/events/upcoming-events\"}",
                "{\"filters\":[],\"reasoning\":\"\"}");

        PipelineResult result = service(aiProvider(ai)).run(looselyRegistered);

        assertTrue(result.correctedAddress() != null,
                "the AI-proposed correction must surface in the result");
        assertEquals("https://www.ema.europa.eu/en/events/upcoming-events",
                result.address().url(),
                "the chain works with (and records) the corrected address");
        assertTrue(result.capabilityEvidence().contains("correction proposed by AI"));
        assertTrue(result.capabilityEvidence().contains("/en/events -> "));
        assertEquals("https://www.ema.europa.eu/en/events/upcoming-events?page={n}",
                result.pageUrlPattern(),
                "the page pattern must match the corrected address");
    }

    @Test
    void inconsistentPagePatternIsRepairedByTheAiNotByStaticRules() {
        AiAssistantPort ai = aiCapabilityRepair(
                // first capability answer: pattern on an unrelated origin
                "{\"pagination\":{\"detected\":true,"
                        + "\"pageUrlPattern\":\"https://pager.example.org/list?page={n}\","
                        + "\"reasoning\":\"\"},"
                        + "\"filtering\":{\"detected\":true,\"reasoning\":\"\"},"
                        + "\"correctedAddress\":null}",
                // repair round: AI rebuilds the pattern on the registered address
                "{\"pagination\":{\"detected\":true,"
                        + "\"pageUrlPattern\":\"https://www.ema.europa.eu/en/events/upcoming-events?page={n}\","
                        + "\"reasoning\":\"rebuilt on the registered address\"},"
                        + "\"filtering\":{\"detected\":true,\"reasoning\":\"\"},"
                        + "\"correctedAddress\":null}");

        PipelineResult result = service(aiProvider(ai)).run(catalog.byId("ADDR-008"));

        assertEquals("https://www.ema.europa.eu/en/events/upcoming-events?page={n}",
                result.pageUrlPattern(),
                "the AI repair round must fix the pattern; code never rewrites it");
        assertTrue(result.capabilityEvidence().contains("repaired by AI"));
    }

    @Test
    void unrepairablePagePatternIsDroppedAndReported() {
        AiAssistantPort ai = aiCapabilityRepair(
                "{\"pagination\":{\"detected\":true,"
                        + "\"pageUrlPattern\":\"https://pager.example.org/list?page={n}\","
                        + "\"reasoning\":\"\"},"
                        + "\"filtering\":{\"detected\":true,\"reasoning\":\"\"},"
                        + "\"correctedAddress\":null}",
                // repair still inconsistent -> dropped, iteration cannot run
                "{\"pagination\":{\"detected\":true,"
                        + "\"pageUrlPattern\":\"https://pager.example.org/other?page={n}\","
                        + "\"reasoning\":\"\"},"
                        + "\"filtering\":{\"detected\":true,\"reasoning\":\"\"},"
                        + "\"correctedAddress\":null}");

        PipelineResult result = service(aiProvider(ai)).run(catalog.byId("ADDR-008"));

        assertTrue(result.pageUrlPattern() == null,
                "an unrepairable pattern must not be used");
        assertTrue(result.capabilityEvidence().contains("rejected"));
        assertEquals(1, result.pagesScraped(),
                "without a valid pattern no additional page may be scraped");
    }

    @Test
    void filterZoneSelectorIsIdentifiedValidatedAndPersisted() {
        AiAssistantPort ai = prompt -> {
            if (prompt.contains("Classify the page")) {
                return "event";
            }
            if (prompt.contains("CAPABILITIES")) {
                return "{\"pagination\":{\"detected\":true,"
                        + "\"pageUrlPattern\":\"https://hadea.ec.europa.eu/events_en?page={n}\","
                        + "\"reasoning\":\"\"},"
                        + "\"filtering\":{\"detected\":true,\"reasoning\":\"\"},"
                        + "\"correctedAddress\":null}";
            }
            if (prompt.contains("FILTER ZONE")) {
                return "{\"filterZoneSelector\":\"form.oe-list-pages-facets-form\","
                        + "\"reasoning\":\"the facet form\"}";
            }
            if (prompt.contains("FILTER controls")) {
                return "{\"filters\":[{\"name\":\"Status\",\"values\":"
                        + "[\"Upcoming and ongoing\",\"Past\"]}],\"reasoning\":\"\"}";
            }
            return "{\"selector\":\"article.ecl-content-item\",\"reasoning\":\"\"}";
        };

        PipelineResult result = service(aiProvider(ai)).run(catalog.byId("ADDR-005"));

        assertEquals("form.oe-list-pages-facets-form", result.filterZoneSelector(),
                "the AI-identified zone selector must be validated (exactly one match) and used");
        assertEquals("form.oe-list-pages-facets-form", store.get("ADDR-005").filterSelector(),
                "the zone selector must be persisted merged into the stored selectors");
        assertEquals(1, result.filters().size());
    }

    @Test
    void filterApplicationRunsOnAFilterableAddress() {
        // The captured filtered response of the SCRAPE connector stands
        // in for what the site returns after the filter request; page 2
        // of the HaDEA events fixture plays that role here.
        ContentScrapePort withFilteredResponse = new ContentScrapePort() {
            @Override
            public ScrapeResult scrape(Address address) {
                return scrape(address, 0);
            }

            @Override
            public ScrapeResult scrape(Address address, int pageIndex) {
                return load("/mvp/samples/addr-005-events-rendered.html");
            }

            @Override
            public ScrapeResult scrapeFiltered(Address address, String filterRequest) {
                assertEquals("oe_event_status=past", filterRequest);
                return load("/mvp/samples/addr-005-events-page2-rendered.html");
            }
        };
        AiAssistantPort ai = aiAnswering("event",
                "{\"selector\":\"article.ecl-content-item\",\"reasoning\":\"\"}",
                "{\"pagination\":{\"detected\":false,\"pageUrlPattern\":null,\"reasoning\":\"\"},"
                        + "\"filtering\":{\"detected\":true,\"reasoning\":\"facet form\"}}",
                "{\"filters\":[{\"name\":\"Event status\",\"values\":"
                        + "[\"Upcoming and ongoing\",\"Past\"]}],\"reasoning\":\"\"}",
                "{\"field\":\"Event status\",\"value\":\"Past\","
                        + "\"request\":\"oe_event_status=past\",\"reasoning\":\"narrows to past events\"}");
        PipelineService service = new PipelineService(withFilteredResponse, simplifyPort,
                extractionPort, selectorStore, paginationDetector, new ExposedFilterDetector(),
                aiProvider(ai), catalog, new UrlSyntaxValidator(), preconfigured(), contentStore(), workDir.toString());

        PipelineResult result = service.run(catalog.byId("ADDR-005"));

        assertEquals("Event status", result.appliedFilterField());
        assertEquals("Past", result.appliedFilterValue());
        assertEquals("oe_event_status=past", result.filterRequest());
        assertTrue(result.filterApplicationNote() == null);
        assertFalse(result.filteredItems().isEmpty(),
                "extraction must run on the filtered response");
        assertTrue(captureDirOf(catalog.byId("ADDR-005"))
                .resolve("content").resolve("FILTER").resolve("scrape-filtered.html")
                .toFile().exists());
        assertTrue(captureDirOf(catalog.byId("ADDR-005"))
                .resolve("content").resolve("FILTER").resolve("simplified-filtered.html")
                .toFile().exists());
    }

    @Test
    void filterApplicationSkipsWhenAiChoosesNoCriterion() {
        // default aiAnswering: the apply prompt answers field=null
        PipelineResult result = service(aiProvider(aiAnswering("event",
                "{\"selector\":\"article.ecl-content-item\",\"reasoning\":\"\"}",
                "{\"pagination\":{\"detected\":false,\"pageUrlPattern\":null,\"reasoning\":\"\"},"
                        + "\"filtering\":{\"detected\":true,\"reasoning\":\"\"}}",
                "{\"filters\":[{\"name\":\"Event status\",\"values\":[\"Past\"]}],\"reasoning\":\"\"}")))
                .run(catalog.byId("ADDR-005"));

        assertTrue(result.appliedFilterField() == null);
        assertTrue(result.filteredItems().isEmpty());
        assertTrue(result.filterApplicationNote().contains("no applicable filter criterion"));
    }

    @Test
    void filterApplicationSkipsWhenConnectorCannotApplyFilters() {
        // Bare SCRAPE port: scrapeFiltered is not implemented -> the
        // default port method rejects the application with a note.
        ContentScrapePort limited = new ContentScrapePort() {
            @Override
            public ScrapeResult scrape(Address address) {
                return load("/mvp/samples/addr-005-events-rendered.html");
            }
        };
        AiAssistantPort ai = aiAnswering("event",
                "{\"selector\":\"article.ecl-content-item\",\"reasoning\":\"\"}",
                "{\"pagination\":{\"detected\":false,\"pageUrlPattern\":null,\"reasoning\":\"\"},"
                        + "\"filtering\":{\"detected\":true,\"reasoning\":\"\"}}",
                "{\"filters\":[{\"name\":\"Event status\",\"values\":[\"Past\"]}],\"reasoning\":\"\"}",
                "{\"field\":\"Event status\",\"value\":\"Past\","
                        + "\"request\":\"oe_event_status=past\",\"reasoning\":\"\"}");
        PipelineService service = new PipelineService(limited, simplifyPort,
                extractionPort, selectorStore, paginationDetector, new ExposedFilterDetector(),
                aiProvider(ai), catalog, new UrlSyntaxValidator(), preconfigured(), contentStore(), workDir.toString());

        PipelineResult result = service.run(catalog.byId("ADDR-005"));

        assertTrue(result.appliedFilterField() == null);
        assertTrue(result.filterApplicationNote().contains("cannot apply"),
                "note must say the connector cannot apply: " + result.filterApplicationNote());
    }

    @Test
    void filterApplicationRejectsCriterionOutsideConnectorSupport() {
        // The connector constrains what it can apply; the AI chooses a
        // criterion outside that list -> the coherence guard rejects the
        // application BEFORE any filtered scrape happens.
        ContentScrapePort constrained = new ContentScrapePort() {
            @Override
            public ScrapeResult scrape(Address address) {
                return load("/mvp/samples/addr-005-events-rendered.html");
            }

            @Override
            public java.util.List<es.jllopez.neis.domain.model.FilterRequestOption> supportedFilterRequests(Address address) {
                return List.of(new es.jllopez.neis.domain.model.FilterRequestOption(
                        "Event status = Past", "facets[status]=past"));
            }

            @Override
            public ScrapeResult scrapeFiltered(Address address, String filterRequest) {
                throw new AssertionError(
                        "no filtered scrape may happen for an off-list criterion");
            }
        };
        AiAssistantPort ai = aiAnswering("event",
                "{\"selector\":\"article.ecl-content-item\",\"reasoning\":\"\"}",
                "{\"pagination\":{\"detected\":false,\"pageUrlPattern\":null,\"reasoning\":\"\"},"
                        + "\"filtering\":{\"detected\":true,\"reasoning\":\"\"}}",
                "{\"filters\":[{\"name\":\"Event status\",\"values\":"
                        + "[\"Upcoming and ongoing\",\"Past\"]}],\"reasoning\":\"\"}",
                "{\"field\":\"Event status\",\"value\":\"Upcoming and ongoing\","
                        + "\"request\":\"oe_event_status=upcoming\","
                        + "\"reasoning\":\"ignored the constraint\"}");
        PipelineService service = new PipelineService(constrained, simplifyPort,
                extractionPort, selectorStore, paginationDetector, new ExposedFilterDetector(),
                aiProvider(ai), catalog, new UrlSyntaxValidator(), preconfigured(), contentStore(), workDir.toString());

        PipelineResult result = service.run(catalog.byId("ADDR-005"));

        assertTrue(result.appliedFilterField() == null);
        assertTrue(result.filterApplicationNote().contains("cannot apply"),
                "the off-list criterion must be rejected: " + result.filterApplicationNote());
        assertTrue(result.filterApplicationNote().contains("supported"),
                "the note must show what the connector supports");
        assertTrue(result.filteredItems().isEmpty());
        assertTrue(!captureDirOf(catalog.byId("ADDR-005"))
                .resolve("content").resolve("FILTER").resolve("scrape-filtered.html")
                .toFile().exists(),
                "no filtered artifacts may be written for an off-list criterion");
    }

    @Test
    void filterApplicationAcceptsSupportedCriterionWithDifferentRequestWording() {
        // The guard matches leniently on the request text OR the chosen
        // value: a supported criterion (value "Past") whose AI-derived
        // request wording differs from the connector's token is applied.
        ContentScrapePort constrained = new ContentScrapePort() {
            @Override
            public ScrapeResult scrape(Address address) {
                return load("/mvp/samples/addr-005-events-rendered.html");
            }

            @Override
            public java.util.List<es.jllopez.neis.domain.model.FilterRequestOption> supportedFilterRequests(Address address) {
                return List.of(new es.jllopez.neis.domain.model.FilterRequestOption(
                        "Event status = Past", "facets[status]=past"));
            }

            @Override
            public ScrapeResult scrapeFiltered(Address address, String filterRequest) {
                return load("/mvp/samples/addr-005-events-page2-rendered.html");
            }
        };
        AiAssistantPort ai = aiAnswering("event",
                "{\"selector\":\"article.ecl-content-item\",\"reasoning\":\"\"}",
                "{\"pagination\":{\"detected\":false,\"pageUrlPattern\":null,\"reasoning\":\"\"},"
                        + "\"filtering\":{\"detected\":true,\"reasoning\":\"\"}}",
                "{\"filters\":[{\"name\":\"Event status\",\"values\":"
                        + "[\"Upcoming and ongoing\",\"Past\"]}],\"reasoning\":\"\"}",
                "{\"field\":\"Event status\",\"value\":\"Past\","
                        + "\"request\":\"oe_event_status=past\",\"reasoning\":\"\"}");
        PipelineService service = new PipelineService(constrained, simplifyPort,
                extractionPort, selectorStore, paginationDetector, new ExposedFilterDetector(),
                aiProvider(ai), catalog, new UrlSyntaxValidator(), preconfigured(), contentStore(), workDir.toString());

        PipelineResult result = service.run(catalog.byId("ADDR-005"));

        assertEquals("Event status", result.appliedFilterField(),
                "a supported criterion with different request wording must be applied");
        assertFalse(result.filteredItems().isEmpty());
        assertTrue(captureDirOf(catalog.byId("ADDR-005"))
                .resolve("content").resolve("FILTER").resolve("scrape-filtered.html")
                .toFile().exists());
    }

    @Test
    void storedFilterCatalogueSkipsTheAiEntirely() {
        // Owner decision 2026-11-12: once a catalogue (fields + ALL
        // values) is stored with the address, the FILTER step reuses it
        // directly — like the item selectors, no AI anywhere.
        store.put("ADDR-005", new StoredSelectors("ADDR-005", "event",
                "article.ecl-content-item", "form#oe-list-pages-facets-form",
                List.of(new FilterDefinition("Event status",
                        List.of("Upcoming and ongoing", "Past"))),
                "ai", "2026-11-12", ""));
        // The capability prompt still runs by design (US-17: the union
        // of AI + heuristics); what must NOT happen is any SELECTOR or
        // FILTER-EXTRACTION call — those come from the store.
        AiAssistantPort aiCapabilityOnly = prompt -> {
            if (prompt.contains("CAPABILITIES")) {
                return "{\"pagination\":{\"detected\":true,\"pageUrlPattern\":null,"
                        + "\"reasoning\":\"\"},\"filtering\":{\"detected\":true,"
                        + "\"reasoning\":\"\"},\"correctedAddress\":null}";
            }
            if (prompt.contains("applying ONE filter")) {
                return "{\"field\":\"Event status\",\"value\":\"Past\","
                        + "\"request\":\"facets[status]=past\",\"reasoning\":\"\"}";
            }
            throw new AssertionError("no selector/filter AI call may happen");
        };
        PipelineService service = new PipelineService(scrapePort, simplifyPort,
                extractionPort, selectorStore, paginationDetector,
                new ExposedFilterDetector(), aiProvider(aiCapabilityOnly),
                catalog, new UrlSyntaxValidator(), preconfigured(), contentStore(), workDir.toString());

        PipelineResult result = service.run(catalog.byId("ADDR-005"));

        assertEquals("stored", result.filterSource(),
                "the definitions must come from the stored catalogue");
        assertEquals(1, result.filters().size());
        assertEquals("Event status", result.filters().get(0).name());
        assertEquals(List.of("Upcoming and ongoing", "Past"),
                result.filters().get(0).values());
    }

    @Test
    void chainFilterSpecAppliesTheCriterionFullyOffline() {
        // The chain's filter spec (owner decision 2026-11-12) replaces
        // the AI: coherence-matched against the stored catalogue and the
        // connector's servable request, then applied. No AI configured
        // at all — heuristics flag the capabilities, the catalogue and
        // the spec do the rest.
        store.put("ADDR-005", new StoredSelectors("ADDR-005", "event",
                "article.ecl-content-item", "form#oe-list-pages-facets-form",
                List.of(new FilterDefinition("Event status",
                        List.of("Upcoming and ongoing", "Past"))),
                "ai", "2026-11-12", ""));
        ContentScrapePort withFilteredResponse = new ContentScrapePort() {
            @Override
            public ScrapeResult scrape(Address address) {
                return load("/mvp/samples/addr-005-events-rendered.html");
            }

            @Override
            public List<FilterRequestOption> supportedFilterRequests(Address address) {
                return List.of(new FilterRequestOption(
                        "Event status = Past", "facets[status]=past"));
            }

            @Override
            public ScrapeResult scrapeFiltered(Address address, String filterRequest) {
                assertEquals("facets[status]=past", filterRequest,
                        "the spec path must send the connector's own request token");
                return load("/mvp/samples/addr-005-events-filtered-rendered.html");
            }
        };
        ObjectProvider<AiAssistantPort> empty = mock(ObjectProvider.class);
        PipelineService service = new PipelineService(withFilteredResponse, simplifyPort,
                extractionPort, selectorStore, paginationDetector,
                new ExposedFilterDetector(), empty,
                catalog, new UrlSyntaxValidator(), preconfigured(), contentStore(), workDir.toString());

        PipelineResult result = service.run(catalog.byId("ADDR-005"), 50, 10,
                new FilterSpec("Event status", "Past"));

        assertEquals("Event status", result.appliedFilterField(),
                "the chain's criterion must be applied without any AI");
        assertEquals("facets[status]=past", result.filterRequest());
        assertFalse(result.filteredItems().isEmpty());
        assertTrue(captureDirOf(catalog.byId("ADDR-005"))
                .resolve("content").resolve("FILTER").resolve("scrape-filtered.html")
                .toFile().exists());
    }

    @Test
    void chainFilterSpecThatMatchesNoCatalogueFieldIsSkippedNotFatal() {
        store.put("ADDR-005", new StoredSelectors("ADDR-005", "event",
                "article.ecl-content-item", "form#oe-list-pages-facets-form",
                List.of(new FilterDefinition("Event status", List.of("Past"))),
                "ai", "2026-11-12", ""));
        ContentScrapePort withFilteredResponse = new ContentScrapePort() {
            @Override
            public ScrapeResult scrape(Address address) {
                return load("/mvp/samples/addr-005-events-rendered.html");
            }

            @Override
            public List<FilterRequestOption> supportedFilterRequests(Address address) {
                return List.of(new FilterRequestOption(
                        "Event status = Past", "facets[status]=past"));
            }

            @Override
            public ScrapeResult scrapeFiltered(Address address, String filterRequest) {
                throw new AssertionError("a spec that matches nothing may not be applied");
            }
        };
        ObjectProvider<AiAssistantPort> empty = mock(ObjectProvider.class);
        PipelineService service = new PipelineService(withFilteredResponse, simplifyPort,
                extractionPort, selectorStore, paginationDetector,
                new ExposedFilterDetector(), empty,
                catalog, new UrlSyntaxValidator(), preconfigured(), contentStore(), workDir.toString());

        PipelineResult result = service.run(catalog.byId("ADDR-005"), 50, 10,
                new FilterSpec("Keyword", "plasma"));

        assertTrue(result.appliedFilterField() == null);
        assertTrue(result.filterApplicationNote().contains("does not match"),
                "the note must name the mismatch: " + result.filterApplicationNote());
        assertTrue(result.filterApplicationNote().contains("Event status"),
                "the note must show the available fields");
    }

    private ScrapeResult load(String resource) {
        try (InputStream in = getClass().getResourceAsStream(resource)) {
            String html = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return new ScrapeResult(html, "text/html; charset=UTF-8", html.length());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
