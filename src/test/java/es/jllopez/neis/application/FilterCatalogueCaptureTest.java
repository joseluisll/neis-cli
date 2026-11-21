package es.jllopez.neis.application;

import es.jllopez.neis.domain.model.Address;
import es.jllopez.neis.domain.model.ScrapeResult;
import es.jllopez.neis.domain.port.AiAssistantPort;
import es.jllopez.neis.domain.model.AddressType;
import es.jllopez.neis.domain.model.StoredSelectors;
import es.jllopez.neis.domain.port.SelectorStorePort;
import es.jllopez.neis.domain.service.ExposedFilterDetector;
import es.jllopez.neis.domain.service.PaginationDetector;
import es.jllopez.neis.domain.service.UrlSyntaxValidator;
import es.jllopez.neis.infrastructure.adapter.catalog.WebpageTypeCatalog;
import es.jllopez.neis.infrastructure.adapter.catalog.WebpageTypeProperties;
import es.jllopez.neis.infrastructure.adapter.scrape.JsoupDomAnalyzer;
import es.jllopez.neis.infrastructure.adapter.scrape.JsoupObstructionClearer;
import es.jllopez.neis.infrastructure.adapter.scrape.JsoupSelectorConnector;
import es.jllopez.neis.infrastructure.adapter.scrape.JsoupSimplifyConnector;
import es.jllopez.neis.testsupport.InMemoryAddressCatalogPort;
import es.jllopez.neis.testsupport.InMemoryContentStorePort;
import es.jllopez.neis.testsupport.InMemoryNewsEventStorePort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * US-06.19 (issue #150): the generic filter-criteria availability
 * rules. (1) The catalogue is captured from the RAW pre-clearing
 * capture DOM — a client-rendered facet panel inside an overlay the
 * (correct) clearing rules remove must still yield its definitions.
 * (2) A preconfigured set (US-06.6 configuration) is recorded as the
 * address's definitions when the page exposes nothing. (3) The FILTER
 * step reuses the stored catalogue without a matching filter-zone
 * selector, and an empty outcome on a flagged address carries the loud
 * note. No site-specific knowledge anywhere — generic HTML5 shapes.
 */
class FilterCatalogueCaptureTest {

    @TempDir
    Path workDir;

    /** The rendered facet panel of a client-rendered listing: the
     * checkbox groups live INSIDE a role=dialog overlay — exactly the
     * shape whose definitions the clearing rules remove afterwards. */
    private static final String RENDERED_FACETS = """
            <html><body>
            <div role="dialog" aria-label="dialogOverlay" class="eui-overlay">
              <form>
                <input type="checkbox" id="checkbox-status-upcoming">
                <label for="checkbox-status-upcoming">Upcoming</label>
                <input type="checkbox" id="checkbox-status-ongoing">
                <label for="checkbox-status-ongoing">Ongoing</label>
                <input type="checkbox" id="checkbox-status-past">
                <label for="checkbox-status-past">Past</label>
                <input type="checkbox" id="checkbox-programme-horizon">
                <label for="checkbox-programme-horizon">Horizon Europe</label>
                <input type="search" id="q" placeholder="Quick search">
              </form>
            </div>
            <ul class="listing"><li>an item</li></ul>
            </body></html>
            """;

    /** A page whose static shell exposes no filter control at all. */
    private static final String PLAIN_PAGE = """
            <html><body><ul class="listing"><li>an item</li></ul></body></html>
            """;

    private final InMemoryAddressCatalogPort catalogPort = new InMemoryAddressCatalogPort(
            List.of(new Address("ADDR-H1", "Hybrid listing", null,
                    "https://example.eu/listing", AddressType.URL_NEWS)));

    /** Recording selector store: saves land here, load reads them. */
    private final Map<String, StoredSelectors> stored = new HashMap<>();

    private final SelectorStorePort selectorStore = new SelectorStorePort() {
        @Override
        public Optional<StoredSelectors> load(String addressId) {
            return Optional.ofNullable(stored.get(addressId));
        }

        @Override
        public void save(StoredSelectors selectors) {
            stored.put(selectors.addressId(), selectors);
        }
    };

    private PipelineService pipeline(String rawHtml) {
        return new PipelineService(
                new ContentScrapePortStub(rawHtml),
                new JsoupSimplifyConnector(), new JsoupSelectorConnector(),
                selectorStore,
                new PaginationDetector(), new ExposedFilterDetector(),
                new EmptyAiProvider(), new AddressCatalogService(catalogPort,
                        new UrlSyntaxValidator()),
                new UrlSyntaxValidator(),
                new PreconfiguredSelectors(new PreconfiguredSelectorProperties(List.of()),
                        new JsoupSelectorConnector()),
                new InMemoryNewsEventStorePort(),
                new ContentStoreService(new InMemoryContentStorePort(), workDir.toString()),
                new JsoupObstructionClearer(),
                new ClearingSelectors(new ClearingProperties(List.of()),
                        new JsoupSelectorConnector()),
                new JsoupDomAnalyzer(), null, workDir.toString());
    }

    @Test
    void webScrapeCapturesTheCatalogueFromTheRawPreClearingDom() {
        TaskWorkService service = service(pipeline(RENDERED_FACETS),
                new PreconfiguredFilters(new PreconfiguredFilterProperties(List.of())));

        service.execute("WEBSCRAPE", catalog().byId("ADDR-H1"),
                Map.of(), null, null, "EXEC-u1");

        StoredSelectors saved = stored.get("ADDR-H1");
        assertEquals(3, saved.filters().size(), "Status + Programme + free text");
        assertEquals("Status", saved.filters().get(0).name());
        assertEquals(List.of("Upcoming", "Ongoing", "Past"),
                saved.filters().get(0).values());
        assertEquals("Programme", saved.filters().get(1).name());
        assertEquals(List.of("Horizon Europe"), saved.filters().get(1).values());
        // one source of truth: the catalogue lives in the selector store
        // the FILTER step reads (verified by the reuse test below)
    }

    @Test
    void preconfiguredSetIsRecordedWhenThePageExposesNothing() {
        TaskWorkService service = service(pipeline(PLAIN_PAGE),
                new PreconfiguredFilters(new PreconfiguredFilterProperties(List.of(
                        new PreconfiguredFilterProperties.Entry("ADDR-H1",
                                "programme", "EU4Health")))));

        service.execute("WEBSCRAPE", catalog().byId("ADDR-H1"),
                Map.of(), null, null, "EXEC-u2");

        StoredSelectors saved = stored.get("ADDR-H1");
        assertEquals(1, saved.filters().size());
        assertEquals("programme", saved.filters().get(0).name());
        assertEquals(List.of("EU4Health"), saved.filters().get(0).values());
    }

    @Test
    void filterStepReusesTheStoredCatalogueWithoutAZoneSelector() {
        // The catalogue captured at WEBSCRAPE time (no filter-zone
        // selector exists — the panel was cleared away): the FILTER
        // step reuses it directly, no AI, no silent fallback.
        stored.put("ADDR-H1", new StoredSelectors("ADDR-H1", "news",
                null, null,
                List.of(new es.jllopez.neis.domain.model.FilterDefinition(
                        "Status", List.of("Upcoming", "Ongoing", "Past"))),
                "test", "2026-11-21", "US-06.19"));
        PipelineService pipeline = pipeline(PLAIN_PAGE);

        PipelineService.FilterOutcome outcome = pipeline.extractFilters(
                flagged(), PLAIN_PAGE);

        assertEquals(List.of("Status"), outcome.filters().stream()
                .map(es.jllopez.neis.domain.model.FilterDefinition::name).toList());
        assertEquals("stored", outcome.source());
    }

    @Test
    void anEmptyOutcomeOnAFlaggedAddressCarriesTheLoudNote() {
        PipelineService pipeline = pipeline(PLAIN_PAGE);

        PipelineService.FilterOutcome outcome = pipeline.extractFilters(
                flagged(), PLAIN_PAGE);

        assertTrue(outcome.filters().isEmpty());
        assertTrue(outcome.note().contains("no filter criteria available"),
                outcome.note());
    }

    // ---- helpers --------------------------------------------------------

    private Address flagged() {
        return catalog().byId("ADDR-H1").withCapabilities(
                java.util.Set.of(es.jllopez.neis.domain.model.AddressCapability.FILTERING));
    }

    private AddressCatalogService catalog() {
        return new AddressCatalogService(catalogPort, new UrlSyntaxValidator());
    }

    private TaskWorkService service(PipelineService pipeline,
                                    PreconfiguredFilters filters) {
        return new TaskWorkService(pipeline, catalog(),
                new InMemoryNewsEventStorePort(),
                new FeedSourceStub(),
                new WebpageTypeCatalog(new WebpageTypeProperties()),
                filters,
                new ClearingSelectors(new ClearingProperties(List.of()),
                        new JsoupSelectorConnector()), null);
    }

    /** All-empty feed source (no feed path exercised here). */
    private static final class FeedSourceStub implements es.jllopez.neis.domain.port.FeedSourcePort {
        @Override public byte[] fetch(Address address) { return new byte[0]; }
        @Override public String toDocumentText(byte[] feedBytes) { return ""; }
        @Override public List<es.jllopez.neis.domain.model.NewsEventItem> parse(
                byte[] feedXml, String addressId, String itemKind,
                int maxItems, String baseUrl) { return List.of(); }
    }

    /** A scrape port serving one fixed document (the "rendered" DOM). */
    private record ContentScrapePortStub(String html)
            implements es.jllopez.neis.domain.port.ContentScrapePort {
        @Override
        public ScrapeResult scrape(Address address) {
            return new ScrapeResult(html, "text/html",
                    html.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
        }

        @Override
        public ScrapeResult scrapeWithFilters(Address address,
                                              Map<String, String> filters,
                                              String connectorOverride) {
            // the preconfigured set is APPLIED at scrape time (US-06.6);
            // the stub serves the same document
            return scrape(address);
        }
    }

    /** AI never configured in these tests. */
    private static final class EmptyAiProvider implements ObjectProvider<AiAssistantPort> {
        @Override
        public AiAssistantPort getObject() {
            throw new IllegalStateException("no AI");
        }

        @Override
        public AiAssistantPort getObject(Object... args) {
            throw new IllegalStateException("no AI");
        }

        @Override
        public AiAssistantPort getIfAvailable() {
            return null;
        }

    }
}
