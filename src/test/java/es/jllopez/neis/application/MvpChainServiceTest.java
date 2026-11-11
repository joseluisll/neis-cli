package es.jllopez.neis.application;

import es.jllopez.neis.domain.model.Address;
import es.jllopez.neis.domain.model.NewsEventItem;
import es.jllopez.neis.domain.model.ScrapeResult;
import es.jllopez.neis.domain.model.SimplifiedContent;
import es.jllopez.neis.domain.port.AiAssistantPort;
import es.jllopez.neis.domain.port.ContentScrapePort;
import es.jllopez.neis.domain.port.SelectorExtractionPort;
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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The predefined static MVP chain (issue #90): SCRAPE - SIMPLIFY -
 * ANALYZE - PRINT, over the real jsoup connectors and a static fixture,
 * with and without the AI integration point.
 */
class MvpChainServiceTest {

    @TempDir
    Path workDir;

    private final AddressCatalogService catalog = new AddressCatalogService();
    private final SimplifyPort simplifyPort = new JsoupSimplifyConnector();
    private final SelectorExtractionPort extractionPort = new JsoupSelectorConnector();
    private final PaginationDetector paginationDetector = new PaginationDetector();

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
                paginationDetector, ai, catalog, workDir.toString());
    }

    @Test
    void chainRunsWithAiClassificationAndSelectors() {
        AiAssistantPort ai = prompt -> """
                {"classification":"news","news_selector":".news-entry","events_selector":null,
                 "reasoning":"The page lists repeated .news-entry items with headings and dates."}""";
        MvpChainResult result = service(aiProvider(ai))
                .run(catalog.byId("ADDR-003"));

        assertTrue(result.analysis().aiAssisted(), "classification must come from the AI");
        assertEquals("news", result.analysis().classification());
        assertEquals(".news-entry", result.selectedSelector());
        assertTrue(result.selectorFromAi(), "AI-identified selector must be the one used");
        assertTrue(result.pagination().paginated(), "fixture has ?pageNumber= pagination");
        assertFalse(result.items().isEmpty(), "extraction must yield items");
        assertEquals("news", result.items().get(0).kind());
        assertTrue(workDir.resolve("ADDR-003").resolve("scrape.html").toFile().exists());
        assertTrue(workDir.resolve("ADDR-003").resolve("simplified.html").toFile().exists());
    }

    @Test
    void chainFallsBackWhenAiNotConfigured() {
        @SuppressWarnings("unchecked")
        ObjectProvider<AiAssistantPort> empty = mock(ObjectProvider.class);
        when(empty.getIfAvailable()).thenReturn(null);

        MvpChainResult result = service(empty).run(catalog.byId("ADDR-003"));

        assertFalse(result.analysis().aiAssisted(), "no AI configured -> fallback analysis");
        assertEquals("news", result.analysis().classification());
        assertTrue(result.analysis().notes().contains("not configured"));
        assertFalse(result.items().isEmpty(), "heuristic fallback selector must still extract");
        assertEquals("news", result.items().get(0).kind());
    }

    @Test
    void chainFallsBackWhenAiFails() {
        AiAssistantPort failing = prompt -> {
            throw new IllegalStateException("provider unreachable");
        };
        MvpChainResult result = service(aiProvider(failing)).run(catalog.byId("ADDR-003"));
        assertFalse(result.analysis().aiAssisted());
        assertTrue(result.analysis().notes().startsWith("AI call failed"));
        assertFalse(result.items().isEmpty());
    }

    @Test
    void runAllCoversTheStaticCatalog() {
        List<MvpChainResult> results = service(aiProvider(prompt ->
                "{\"classification\":\"other\",\"news_selector\":null,\"events_selector\":null,\"reasoning\":\"\"}"))
                .runAll();
        assertEquals(catalog.list().size(), results.size());
        results.forEach(r -> assertTrue(
                workDir.resolve(r.address().id()).resolve("simplified.html").toFile().exists()));
    }
}
