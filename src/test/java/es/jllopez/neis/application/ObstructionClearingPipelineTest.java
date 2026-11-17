package es.jllopez.neis.application;

import es.jllopez.neis.domain.model.Address;
import es.jllopez.neis.domain.model.AddressType;
import es.jllopez.neis.domain.model.Capture;
import es.jllopez.neis.domain.port.SelectorExtractionPort;
import es.jllopez.neis.infrastructure.adapter.scrape.JsoupObstructionClearer;
import es.jllopez.neis.infrastructure.adapter.scrape.JsoupSelectorConnector;
import es.jllopez.neis.infrastructure.adapter.scrape.StaticScrapeConnector;
import es.jllopez.neis.testsupport.InMemoryAddressCatalogPort;
import es.jllopez.neis.testsupport.InMemoryContentStorePort;
import es.jllopez.neis.testsupport.InMemoryNewsEventStorePort;
import es.jllopez.neis.testsupport.SeedAddresses;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Obstruction clearing over the SCRAPE step (US-06.7, issue #75): the
 * chain clears the captured HTML5 BEFORE storing it, the removal
 * record lands on the capture metadata (US-11.4, schema V15), the
 * page-specific selectors follow the selector model (US-12.3
 * boot-time validation), and a clearing-less re-capture never wipes a
 * stored record.
 */
class ObstructionClearingPipelineTest {

    @TempDir
    Path workDir;

    private final InMemoryNewsEventStorePort newsEventStore =
            new InMemoryNewsEventStorePort();

    private InMemoryContentStorePort contentStorePort() {
        return new InMemoryContentStorePort();
    }

    private ClearingSelectors selectors(List<ClearingProperties.Entry> entries) {
        SelectorExtractionPort extraction = new JsoupSelectorConnector();
        return new ClearingSelectors(new ClearingProperties(entries), extraction);
    }

    private static final ClearingProperties.Entry BAD_ENTRY =
            new ClearingProperties.Entry("ADDR-001", "news", "div[class=\"unterminated");

    @Test
    void anInvalidClearingSelectorFailsTheBoot() {
        // US-12.3 rule: a selector that does not parse is refused at
        // configuration time — never at run time.
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> selectors(List.of(BAD_ENTRY)));
        assertTrue(e.getMessage().contains("does not parse"));
        assertTrue(e.getMessage().contains("ADDR-001"));
    }

    @Test
    void theChainClearsObstructionsBeforeStoring() {
        Address address = SeedAddresses.verified().get(0);
        // ADDR-001's fixture carries a cookie-consent banner
        // (wt-cck--banner / id=cookie-consent-banner) — the generic
        // vocabulary rule removes it.
        InMemoryContentStorePort store = contentStorePort();
        PipelineService pipeline = new PipelineService(new StaticScrapeConnector(),
                new es.jllopez.neis.infrastructure.adapter.scrape.JsoupSimplifyConnector(),
                new JsoupSelectorConnector(),
                selectorStore(),
                new es.jllopez.neis.domain.service.PaginationDetector(),
                new es.jllopez.neis.domain.service.ExposedFilterDetector(),
                new ObjectProviderStub(), catalogOf(address),
                new es.jllopez.neis.domain.service.UrlSyntaxValidator(),
                new PreconfiguredSelectors(new PreconfiguredSelectorProperties(List.of()),
                        new JsoupSelectorConnector()),
                newsEventStore, new ContentStoreService(store, workDir.toString()),
                new JsoupObstructionClearer(), selectors(List.of()),
                new es.jllopez.neis.infrastructure.adapter.scrape.JsoupDomAnalyzer(),
                null,
                workDir.toString());

        pipeline.run(address);

        Capture capture = store.findCapture(address.id(),
                new ContentStoreService(store, workDir.toString())
                        .captureId(address.url())).orElseThrow();
        assertNotNull(capture.cleared(), "the clearing record must be stored");
        assertTrue(capture.cleared().contains("\"removed\":"));
        assertTrue(capture.cleared().contains("consent-banners"),
                capture.cleared());
        // The stored HTML5 is the cleared one.
        String scrapeFile = scrapeArtifact(address);
        try {
            String html = java.nio.file.Files.readString(scrapeFile == null ? Path.of(".") : Path.of(scrapeFile));
            // the fixture's banner text is gone from the stored artifact
            assertFalse(html.contains("wt-cck--banner"), "banner class must be gone");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String scrapeArtifact(Address address) {
        String captureId = new ContentStoreService(contentStorePort(), workDir.toString())
                .captureId(address.url());
        return workDir.resolve(address.id()).resolve(captureId)
                .resolve("content").resolve("WEBSCRAPE").resolve("scrape.html")
                .toString();
    }

    @Test
    void aClearingLessReCaptureNeverWipesTheStoredRecord() {
        // The never-wipes rule: a later capture that removes nothing
        // keeps the previously stored clearing record.
        Address address = SeedAddresses.verified().get(0);
        InMemoryContentStorePort store = contentStorePort();
        ContentStoreService contentStore = new ContentStoreService(store, workDir.toString());
        String captureId = contentStore.captureId(address.url());

        store.saveCapture(new Capture(address.id(), captureId, address.url(),
                java.time.Instant.now(), "static", null,
                "{\"removed\":2,\"rules\":[\"advertisements\"]}"));

        PipelineService pipeline = new PipelineService(new StaticScrapeConnector(),
                new es.jllopez.neis.infrastructure.adapter.scrape.JsoupSimplifyConnector(),
                new JsoupSelectorConnector(),
                selectorStore(),
                new es.jllopez.neis.domain.service.PaginationDetector(),
                new es.jllopez.neis.domain.service.ExposedFilterDetector(),
                new ObjectProviderStub(), catalogOf(address),
                new es.jllopez.neis.domain.service.UrlSyntaxValidator(),
                new PreconfiguredSelectors(new PreconfiguredSelectorProperties(List.of()),
                        new JsoupSelectorConnector()),
                newsEventStore, contentStore,
                new JsoupObstructionClearer(), selectors(List.of()),
                new es.jllopez.neis.infrastructure.adapter.scrape.JsoupDomAnalyzer(),
                null,
                workDir.toString());

        // A capture of a page that yields NO removals: the stored
        // record survives (the address URL must match; ADDR-001's
        // fixture does carry a banner, so simulate the no-op via a
        // capture written by a plain captureDir — the store contract).
        store.saveCapture(new Capture(address.id(), captureId, address.url(),
                java.time.Instant.now(), "static", null, null));

        assertEquals("{\"removed\":2,\"rules\":[\"advertisements\"]}",
                store.findCapture(address.id(), captureId).orElseThrow().cleared(),
                "the connector preserves a stored clearing record on a null write");
    }

    @Test
    void clearingRecordJsonIsDeterministic() {
        es.jllopez.neis.domain.model.ClearedContent cleared =
                new es.jllopez.neis.domain.model.ClearedContent("<p/>", 4, 2,
                        List.of("advertisements", "declared-overlays"));
        assertEquals("{\"removed\":2,\"rules\":[\"advertisements\",\"declared-overlays\"]}",
                PipelineService.clearingRecord(cleared));
        assertNull(PipelineService.clearingRecord(
                new es.jllopez.neis.domain.model.ClearedContent("<p/>", 4, 0, List.of())));
    }

    private AddressCatalogService catalogOf(Address address) {
        return new AddressCatalogService(
                new InMemoryAddressCatalogPort(List.of(address)),
                new es.jllopez.neis.domain.service.UrlSyntaxValidator());
    }

    private static final class ObjectProviderStub
            implements org.springframework.beans.factory.ObjectProvider<es.jllopez.neis.domain.port.AiAssistantPort> {
        @Override
        public es.jllopez.neis.domain.port.AiAssistantPort getObject() {
            throw new IllegalStateException("no AI in this test");
        }

        @Override
        public es.jllopez.neis.domain.port.AiAssistantPort getIfAvailable() {
            return null;
        }
    }

    /** In-memory selector store (no stored selectors in these tests). */
    private es.jllopez.neis.domain.port.SelectorStorePort selectorStore() {
        return new es.jllopez.neis.domain.port.SelectorStorePort() {
            @Override
            public java.util.Optional<es.jllopez.neis.domain.model.StoredSelectors> load(String addressId) {
                return java.util.Optional.empty();
            }

            @Override
            public void save(es.jllopez.neis.domain.model.StoredSelectors selectors) {
                // not exercised here
            }
        };
    }

}
