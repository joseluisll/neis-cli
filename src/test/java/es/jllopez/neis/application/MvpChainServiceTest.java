package es.jllopez.neis.application;

import es.jllopez.neis.domain.model.Address;
import es.jllopez.neis.domain.model.AddressCapability;
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
class MvpChainServiceTest {

    @TempDir
    Path workDir;

    private final AddressCatalogService catalog = new AddressCatalogService();
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

    private MvpChainService service(ObjectProvider<AiAssistantPort> ai) {
        return new MvpChainService(scrapePort, simplifyPort, extractionPort,
                selectorStore, paginationDetector, new ExposedFilterDetector(),
                ai, catalog, workDir.toString());
    }

    /** AI stub answering the task-specific prompt catalog (issue #90). */
    private AiAssistantPort aiAnswering(String classification, String selectorJson) {
        return aiAnswering(classification, selectorJson,
                "{\"pagination\":{\"detected\":false,\"pageUrlPattern\":null,"
                        + "\"reasoning\":\"\"},"
                        + "\"filtering\":{\"detected\":false,\"reasoning\":\"\"}}",
                "{\"filters\":[],\"reasoning\":\"none\"}");
    }

    /** AI stub with explicit capability-detection and filter answers (US-17). */
    private AiAssistantPort aiAnswering(String classification, String selectorJson,
                                        String capabilityJson, String filtersJson) {
        return prompt -> {
            if (prompt.contains("Classify the page")) {
                return classification;
            }
            if (prompt.contains("CAPABILITIES")) {
                return capabilityJson;
            }
            if (prompt.contains("FILTER controls")) {
                return filtersJson;
            }
            return selectorJson;
        };
    }

    @Test
    void chainIdentifiesSelectorWithAiAndPersistsIt() {
        AiAssistantPort ai = aiAnswering("news",
                "{\"selector\":\"sedia-result-card-news-translation\",\"reasoning\":\"repeated news result cards\"}");
        MvpChainResult result = service(aiProvider(ai)).run(catalog.byId("ADDR-003"));

        assertEquals("ai", result.selectorSource());
        assertTrue(result.analysis().aiAssisted(), "classification must come from the AI");
        assertEquals("news", result.analysis().classification());
        assertEquals("sedia-result-card-news-translation", result.selectedSelector());
        assertTrue(result.pagination().paginated(), "rendered fixture has ?pageNumber= pagination");
        assertFalse(result.items().isEmpty(), "extraction must yield items");
        assertEquals("news", result.items().get(0).kind());
        assertTrue(workDir.resolve("ADDR-003").resolve("scrape.html").toFile().exists());
        assertTrue(workDir.resolve("ADDR-003").resolve("simplified.html").toFile().exists());

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
        // capability-detection block still uses its own AI prompt (US-17).
        AiAssistantPort selectorAiMustNotCall = prompt -> {
            if (prompt.contains("CSS selector")) {
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

        MvpChainResult result = service(aiProvider(selectorAiMustNotCall)).run(catalog.byId("ADDR-003"));

        assertEquals("stored", result.selectorSource());
        assertFalse(result.analysis().aiAssisted());
        assertFalse(result.items().isEmpty(), "stored selector must still extract items");
        assertTrue(result.analysis().notes().contains("AI skipped"));
    }

    @Test
    void staleStoredSelectorTriggersReIdentification() {
        store.put("ADDR-003", new StoredSelectors("ADDR-003", "news",
                ".does-not-match-anymore", "ai", "2026-11-01", "stale"));
        AiAssistantPort ai = aiAnswering("news",
                "{\"selector\":\"sedia-result-card-news-translation\",\"reasoning\":\"refreshed\"}");

        MvpChainResult result = service(aiProvider(ai)).run(catalog.byId("ADDR-003"));

        assertEquals("ai", result.selectorSource(), "stale selector must trigger AI re-run");
        assertEquals("sedia-result-card-news-translation",
                store.get("ADDR-003").selector(), "store must be refreshed");
    }

    @Test
    void chainExtractsRealRenderedNewsFromAddr001() {
        MvpChainResult result = service(aiProvider(aiAnswering("news",
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

        MvpChainResult result = service(empty).run(catalog.byId("ADDR-003"));

        assertEquals("fallback", result.selectorSource());
        assertFalse(result.analysis().aiAssisted(), "no AI configured -> fallback analysis");
        assertEquals("news", result.analysis().classification());
        assertTrue(result.analysis().notes().contains("not configured"));
        assertFalse(result.items().isEmpty(), "heuristic fallback selector must still extract");
        assertEquals("news", result.items().get(0).kind());
        assertTrue(!store.containsKey("ADDR-003"), "fallback selectors are never persisted");
    }

    @Test
    void chainFallsBackWhenAiFails() {
        AiAssistantPort failing = prompt -> {
            throw new IllegalStateException("provider unreachable");
        };
        MvpChainResult result = service(aiProvider(failing)).run(catalog.byId("ADDR-003"));
        assertEquals("fallback", result.selectorSource());
        assertTrue(result.analysis().notes().startsWith("AI call failed"));
        assertFalse(result.items().isEmpty());
    }

    @Test
    void runAllCoversTheStaticCatalog() {
        List<MvpChainResult> results = service(aiProvider(aiAnswering("other",
                "{\"selector\":null,\"reasoning\":\"none\"}")))
                .runAll();
        assertEquals(catalog.list().size(), results.size());
        results.forEach(r -> {
            assertEquals("fallback", r.selectorSource());
            assertTrue(workDir.resolve(r.address().id())
                    .resolve("simplified.html").toFile().exists());
        });
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

        MvpChainResult result = service(aiProvider(ai)).run(catalog.byId("ADDR-005"));

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
        MvpChainResult result = service(aiProvider(aiAnswering("event",
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

        MvpChainResult result = service(empty).run(catalog.byId("ADDR-005"));

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

        MvpChainResult result = service(aiProvider(ai)).run(catalog.byId("ADDR-006"));

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

        MvpChainResult result = service(aiProvider(ai)).run(catalog.byId("ADDR-008"));

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

        MvpChainResult result = service(aiProvider(ai)).run(catalog.byId("ADDR-007"));

        assertTrue(result.address().hasCapability(AddressCapability.PAGINATION));
        assertTrue(result.address().hasCapability(AddressCapability.FILTERING));
        assertEquals(50, result.items().size(),
                "10 news/page over 5 fixture pages must reach the MAX_ITEMS cap");
        assertEquals(5, result.pagesScraped());
    }
}
