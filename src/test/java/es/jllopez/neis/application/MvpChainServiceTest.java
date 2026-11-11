package es.jllopez.neis.application;

import es.jllopez.neis.domain.model.Address;
import es.jllopez.neis.domain.model.ScrapeResult;
import es.jllopez.neis.domain.model.StoredSelectors;
import es.jllopez.neis.domain.port.AiAssistantPort;
import es.jllopez.neis.domain.port.ContentScrapePort;
import es.jllopez.neis.domain.port.SelectorExtractionPort;
import es.jllopez.neis.domain.port.SelectorStorePort;
import es.jllopez.neis.domain.port.SimplifyPort;
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

    private final ContentScrapePort scrapePort = address -> {
        String fixture = "/mvp/samples/" + switch (address.id()) {
            case "ADDR-001" -> "addr-001-news.html";
            case "ADDR-002" -> "addr-002-events.html";
            case "ADDR-003" -> "addr-003-news-spa-rendered.html";
            case "ADDR-004" -> "addr-004-events-spa-rendered.html";
            default -> throw new IllegalStateException("unexpected " + address.id());
        };
        try (InputStream in = getClass().getResourceAsStream(fixture)) {
            String html = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return new ScrapeResult(html, "text/html; charset=UTF-8", html.length());
        } catch (Exception e) {
            throw new IllegalStateException(e);
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
                selectorStore, paginationDetector, ai, catalog, workDir.toString());
    }

    /** AI stub answering the task-specific prompt catalog (issue #90). */
    private AiAssistantPort aiAnswering(String classification, String selectorJson) {
        return prompt -> prompt.contains("Classify the page")
                ? classification
                : selectorJson;
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
        // AI that must not be reached: any call fails the test.
        AiAssistantPort mustNotCall = prompt -> {
            throw new AssertionError("AI must be skipped when a stored selector works");
        };

        MvpChainResult result = service(aiProvider(mustNotCall)).run(catalog.byId("ADDR-003"));

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
}
