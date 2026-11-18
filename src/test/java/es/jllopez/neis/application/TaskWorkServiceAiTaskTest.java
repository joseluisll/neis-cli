package es.jllopez.neis.application;

import es.jllopez.neis.domain.model.Address;
import es.jllopez.neis.domain.model.AddressType;
import es.jllopez.neis.domain.model.Capture;
import es.jllopez.neis.domain.model.StoredSelectors;
import es.jllopez.neis.domain.port.AiAssistantPort;
import es.jllopez.neis.domain.port.SelectorStorePort;
import es.jllopez.neis.testsupport.InMemoryContentStorePort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * The AI task type (US-13.1..4, issues #50..53): schedulable and
 * chainable like WEBSCRAPE/SIMPLIFY; classification into
 * news/event/other recorded as capture metadata (US-13.2); the
 * news/events selector identified over the simplified content and
 * persisted with the address (US-13.3/.4). Without a configured AI
 * integration point the step fails loud — an AI task that silently ran
 * heuristics would be something else than what the user scheduled.
 */
class TaskWorkServiceAiTaskTest {

    @TempDir
    Path workDir;

    private final Address address = new Address("ADDR-001", "Source",
            "https://example.eu/news", AddressType.URL_NEWS);

    private static final String NEWS_HTML = """
            <html><body><main><h1>Listing</h1>
            <ul class="items"><li><a href="/item/1">Item one</a></li>
            <li><a href="/item/2">Item two</a></li></ul>
            </main></body></html>
            """;

    /** An AI answering the classification and selector prompts. */
    private AiAssistantPort aiAnswering(String classification, String selectorJson) {
        return prompt -> {
            if (prompt.contains("Classify the page as exactly one of")) {
                return classification;
            }
            return selectorJson;
        };
    }

    /** AI always present. */
    private ObjectProvider<AiAssistantPort> provider(AiAssistantPort ai) {
        return new ObjectProvider<>() {
            @Override
            public AiAssistantPort getObject() {
                return ai;
            }

            @Override
            public AiAssistantPort getIfAvailable() {
                return ai;
            }
        };
    }

    private ObjectProvider<AiAssistantPort> emptyProvider() {
        return new ObjectProvider<>() {
            @Override
            public AiAssistantPort getObject() {
                throw new IllegalStateException("no AI");
            }

            @Override
            public AiAssistantPort getIfAvailable() {
                return null;
            }
        };
    }

    private record SelectorMemory(Optional<StoredSelectors> stored,
                                  java.util.ArrayList<StoredSelectors> saved)
            implements SelectorStorePort {
        @Override
        public Optional<StoredSelectors> load(String addressId) {
            return stored;
        }

        @Override
        public void save(StoredSelectors selectors) {
            saved.add(selectors);
        }
    }

    private TaskWorkService service(AiAssistantPort ai, SelectorStorePort selectorStore) {
        PipelineService pipeline = new PipelineService(
                new es.jllopez.neis.infrastructure.adapter.scrape.StaticScrapeConnector() {
                    @Override
                    public es.jllopez.neis.domain.model.ScrapeResult scrape(Address a) {
                        return new es.jllopez.neis.domain.model.ScrapeResult(
                                NEWS_HTML, "text/html", NEWS_HTML.length());
                    }
                },
                new es.jllopez.neis.infrastructure.adapter.scrape.JsoupSimplifyConnector(),
                new es.jllopez.neis.infrastructure.adapter.scrape.JsoupSelectorConnector(),
                selectorStore,
                new es.jllopez.neis.domain.service.PaginationDetector(),
                new es.jllopez.neis.domain.service.ExposedFilterDetector(),
                provider(ai),
                new AddressCatalogService(
                        new es.jllopez.neis.testsupport.InMemoryAddressCatalogPort(
                                List.of(address)),
                        new es.jllopez.neis.domain.service.UrlSyntaxValidator()),
                new es.jllopez.neis.domain.service.UrlSyntaxValidator(),
                new PreconfiguredSelectors(
                        new PreconfiguredSelectorProperties(List.of()),
                        new es.jllopez.neis.infrastructure.adapter.scrape.JsoupSelectorConnector()),
                new es.jllopez.neis.testsupport.InMemoryNewsEventStorePort(),
                new ContentStoreService(new InMemoryContentStorePort(), workDir.toString()),
                new es.jllopez.neis.infrastructure.adapter.scrape.JsoupObstructionClearer(),
                new ClearingSelectors(new ClearingProperties(List.of()),
                        new es.jllopez.neis.infrastructure.adapter.scrape.JsoupSelectorConnector()),
                new es.jllopez.neis.infrastructure.adapter.scrape.JsoupDomAnalyzer(),
                null,
                workDir.toString());
        return new TaskWorkService(pipeline,
                mock(AddressCatalogService.class),
                new es.jllopez.neis.testsupport.InMemoryNewsEventStorePort(),
                new es.jllopez.neis.infrastructure.adapter.feed.RomeFeedConnector(5, 1048576),
                new es.jllopez.neis.infrastructure.adapter.catalog.WebpageTypeCatalog(
                        new es.jllopez.neis.infrastructure.adapter.catalog.WebpageTypeProperties()),
                new PreconfiguredFilters(new PreconfiguredFilterProperties(List.of())),
                new ClearingSelectors(new ClearingProperties(List.of()),
                        new es.jllopez.neis.infrastructure.adapter.scrape.JsoupSelectorConnector()),
                null);
    }

    @Test
    void theAiTaskClassifiesAndIdentifiesTheNewsSelector() {
        SelectorMemory selectors = new SelectorMemory(Optional.empty(), new java.util.ArrayList<>());
        String outcome = service(
                aiAnswering("news",
                        "{\"selector\":\"ul.items li\",\"reasoning\":\"list items\"}"),
                selectors).execute("AI", address, Map.of());
        assertTrue(outcome.startsWith("AI: classification=news"), outcome);
        assertTrue(outcome.contains("ul.items li"), outcome);
        assertTrue(outcome.contains("(ai)"), outcome);
        // US-13.3: the identified selector is persisted for reuse.
        assertEquals(1, selectors.saved().size());
        assertEquals("ul.items li", selectors.saved().get(0).selector());
        assertEquals("ai", selectors.saved().get(0).discoveredBy());
    }

    @Test
    void theClassificationIsRecordedOnTheCaptureMetadata() {
        RecordingStore store = new RecordingStore();
        serviceFor(address, aiAnswering("event",
                        "{\"selector\":\"ul.items li\",\"reasoning\":\"\"}"),
                new SelectorMemory(Optional.empty(), new java.util.ArrayList<>()),
                store).execute("AI", address, Map.of());
        Capture capture = store.findCapture(address.id(),
                store.lastCaptureId()).orElseThrow();
        assertNotNull(capture.classification(), "the AI verdict must be recorded");
        assertTrue(capture.classification().contains("\"classification\":\"event\""),
                capture.classification());
    }

    @Test
    void anUnknownTaskParameterIsRefused() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service(aiAnswering("news", "{}"),
                        new SelectorMemory(Optional.empty(), new java.util.ArrayList<>()))
                        .execute("AI", address, Map.of("variant", "lean")));
        assertTrue(e.getMessage().contains("variant"), e.getMessage());
    }

    @Test
    void withoutAConfiguredAiTheStepFailsLoud() {
        TaskWorkService noAi = serviceWithAi(emptyProvider());
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> noAi.execute("AI", address, Map.of()));
        assertTrue(e.getMessage().contains("AI integration point"), e.getMessage());
    }

    @Test
    void theEventsSelectorIsIdentifiedForAnEventsAddress() {
        Address events = new Address("ADDR-002", "Events",
                "https://example.eu/events", AddressType.URL_EVENTS);
        SelectorMemory selectors = new SelectorMemory(Optional.empty(), new ArrayList<>());
        String outcome = serviceFor(events,
                aiAnswering("event",
                        "{\"selector\":\"ul.items li\",\"reasoning\":\"\"}"),
                selectors).execute("AI", events, Map.of());
        assertTrue(outcome.startsWith("AI: classification=event"), outcome);
        assertEquals("ul.items li", selectors.saved().get(0).selector());
    }

    // ---- plumbing ----

    /** Exposes the last-written capture id for the metadata assertions. */
    private static final class RecordingStore extends InMemoryContentStorePort {
        private String lastCaptureId;

        String lastCaptureId() {
            return lastCaptureId;
        }

        @Override
        public Capture saveCapture(Capture capture) {
            lastCaptureId = capture.captureId();
            return super.saveCapture(capture);
        }
    }

    private TaskWorkService serviceWithAi(ObjectProvider<AiAssistantPort> aiProvider) {
        return new TaskWorkService(pipeline(aiProvider,
                        new InMemoryContentStorePort(),
                        new SelectorMemory(Optional.empty(), new java.util.ArrayList<>())),
                mock(AddressCatalogService.class),
                new es.jllopez.neis.testsupport.InMemoryNewsEventStorePort(),
                new es.jllopez.neis.infrastructure.adapter.feed.RomeFeedConnector(5, 1048576),
                new es.jllopez.neis.infrastructure.adapter.catalog.WebpageTypeCatalog(
                        new es.jllopez.neis.infrastructure.adapter.catalog.WebpageTypeProperties()),
                new PreconfiguredFilters(new PreconfiguredFilterProperties(List.of())),
                new ClearingSelectors(new ClearingProperties(List.of()),
                        new es.jllopez.neis.infrastructure.adapter.scrape.JsoupSelectorConnector()),
                null);
    }

    private TaskWorkService serviceWithStore(AiAssistantPort ai,
                                             InMemoryContentStorePort store) {
        return new TaskWorkService(
                pipeline(provider(ai), store,
                        new SelectorMemory(Optional.empty(), new java.util.ArrayList<>())),
                mock(AddressCatalogService.class),
                new es.jllopez.neis.testsupport.InMemoryNewsEventStorePort(),
                new es.jllopez.neis.infrastructure.adapter.feed.RomeFeedConnector(5, 1048576),
                new es.jllopez.neis.infrastructure.adapter.catalog.WebpageTypeCatalog(
                        new es.jllopez.neis.infrastructure.adapter.catalog.WebpageTypeProperties()),
                new PreconfiguredFilters(new PreconfiguredFilterProperties(List.of())),
                new ClearingSelectors(new ClearingProperties(List.of()),
                        new es.jllopez.neis.infrastructure.adapter.scrape.JsoupSelectorConnector()),
                null);
    }

    private TaskWorkService service(AiAssistantPort ai, SelectorStorePort selectorStore,
                                    Address overAddress) {
        return serviceFor(overAddress, ai, selectorStore);
    }

    /** A service whose pipeline is built around the given address. */
    private TaskWorkService serviceFor(Address over, AiAssistantPort ai,
                                       SelectorStorePort selectorStore) {
        return serviceFor(over, ai, selectorStore, new InMemoryContentStorePort());
    }

    /** Same, with an explicit content store (metadata assertions). */
    private TaskWorkService serviceFor(Address over, AiAssistantPort ai,
                                       SelectorStorePort selectorStore,
                                       InMemoryContentStorePort store) {
        return new TaskWorkService(
                pipelineFor(over, provider(ai), store, selectorStore),
                mock(AddressCatalogService.class),
                new es.jllopez.neis.testsupport.InMemoryNewsEventStorePort(),
                new es.jllopez.neis.infrastructure.adapter.feed.RomeFeedConnector(5, 1048576),
                new es.jllopez.neis.infrastructure.adapter.catalog.WebpageTypeCatalog(
                        new es.jllopez.neis.infrastructure.adapter.catalog.WebpageTypeProperties()),
                new PreconfiguredFilters(new PreconfiguredFilterProperties(List.of())),
                new ClearingSelectors(new ClearingProperties(List.of()),
                        new es.jllopez.neis.infrastructure.adapter.scrape.JsoupSelectorConnector()),
                null);
    }

    private PipelineService pipelineFor(Address over,
                                        ObjectProvider<AiAssistantPort> aiProvider,
                                        InMemoryContentStorePort store,
                                        SelectorStorePort selectorStore) {
        return new PipelineService(
                new es.jllopez.neis.infrastructure.adapter.scrape.StaticScrapeConnector() {
                    @Override
                    public es.jllopez.neis.domain.model.ScrapeResult scrape(Address a) {
                        return new es.jllopez.neis.domain.model.ScrapeResult(
                                NEWS_HTML, "text/html", NEWS_HTML.length());
                    }
                },
                new es.jllopez.neis.infrastructure.adapter.scrape.JsoupSimplifyConnector(),
                new es.jllopez.neis.infrastructure.adapter.scrape.JsoupSelectorConnector(),
                selectorStore,
                new es.jllopez.neis.domain.service.PaginationDetector(),
                new es.jllopez.neis.domain.service.ExposedFilterDetector(),
                aiProvider,
                new AddressCatalogService(
                        new es.jllopez.neis.testsupport.InMemoryAddressCatalogPort(
                                List.of(over)),
                        new es.jllopez.neis.domain.service.UrlSyntaxValidator()),
                new es.jllopez.neis.domain.service.UrlSyntaxValidator(),
                new PreconfiguredSelectors(
                        new PreconfiguredSelectorProperties(List.of()),
                        new es.jllopez.neis.infrastructure.adapter.scrape.JsoupSelectorConnector()),
                new es.jllopez.neis.testsupport.InMemoryNewsEventStorePort(),
                new ContentStoreService(store, workDir.toString()),
                new es.jllopez.neis.infrastructure.adapter.scrape.JsoupObstructionClearer(),
                new ClearingSelectors(new ClearingProperties(List.of()),
                        new es.jllopez.neis.infrastructure.adapter.scrape.JsoupSelectorConnector()),
                new es.jllopez.neis.infrastructure.adapter.scrape.JsoupDomAnalyzer(),
                null,
                workDir.toString());
    }

    private PipelineService pipeline(ObjectProvider<AiAssistantPort> aiProvider,
                                     InMemoryContentStorePort store,
                                     SelectorStorePort selectorStore) {
        return pipelineFor(address, aiProvider, store, selectorStore);
    }
}
