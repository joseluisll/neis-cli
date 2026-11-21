package es.jllopez.neis.application;

import es.jllopez.neis.domain.model.Address;
import es.jllopez.neis.domain.model.AddressType;
import es.jllopez.neis.domain.model.NewsEventItem;
import es.jllopez.neis.domain.model.VerificationStatus;
import es.jllopez.neis.domain.port.SelectorStorePort;
import es.jllopez.neis.domain.model.StoredSelectors;
import es.jllopez.neis.domain.service.PaginationDetector;
import es.jllopez.neis.domain.service.UrlSyntaxValidator;
import es.jllopez.neis.infrastructure.adapter.scrape.JsoupDomAnalyzer;
import es.jllopez.neis.infrastructure.adapter.scrape.JsoupObstructionClearer;
import es.jllopez.neis.infrastructure.adapter.scrape.JsoupSelectorConnector;
import es.jllopez.neis.infrastructure.adapter.scrape.JsoupSimplifyConnector;
import es.jllopez.neis.testsupport.InMemoryAddressCatalogPort;
import es.jllopez.neis.testsupport.InMemoryContentStorePort;
import es.jllopez.neis.testsupport.InMemoryNewsEventStorePort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * US-06.16 (issue #148): the STRICT same-host extraction filter —
 * an address's listing yields items from the address's own host.
 * Owner decision 2026-11-20 (disposition of #147): no allow-list, no
 * knob; the MujerHoy sister-brand consequence is accepted and pinned.
 * One rule, one place on the application path — the pipeline, the
 * standalone WEBSCRAPE step and the FEED parse all share it.
 */
class SameHostExtractionTest {

    private static final String MIXED_LISTING = """
            <html><body><main><ul class='news-list'>
            <li><h3>Noticia local de la portada</h3>
              <a href='https://www.abc.es/local/noticia-a'>leer mas</a></li>
            <li><h3>Patrocinado del content factory</h3>
              <a href='https://www.abc.es/contentfactory/post/2026/11/20/patrocinado'>leer mas</a></li>
            <li><h3>Segmento de radio del modulo externo</h3>
              <a href='https://www.cope.es/programas/la-linterna/audio'>escuchar</a></li>
            <li><h3>Sindicacion de la marca hermana</h3>
              <a href='https://www.mujerhoy.com/vida/noticia-b'>leer mas</a></li>
            </ul></main></body></html>
            """;

    private static final String MIXED_FEED = """
            <?xml version="1.0" encoding="UTF-8"?>
            <rss version="2.0"><channel>
              <title>Portada feed</title>
              <item><title>Entrada propia del feed</title>
                <link>https://www.abc.es/feed/propia-1</link></item>
              <item><title>Enlace externo incrustado</title>
                <link>https://www.cope.es/externo/enlace</link></item>
            </channel></rss>
            """;

    @TempDir
    Path workDir;

    private final InMemoryNewsEventStorePort newsEventStore =
            new InMemoryNewsEventStorePort();

    private final AddressCatalogService catalogService = new AddressCatalogService(
            new InMemoryAddressCatalogPort(List.of(abc(), feedAddress())),
            new UrlSyntaxValidator());

    private Address feedAddress() {
        return new Address("ADDR-021", "ABC feed", null,
                "https://www.abc.es/rss/portada", AddressType.URL_NEWS)
                .withVerification(VerificationStatus.VERIFIED, "2026-11-21T00:00:00Z");
    }

    private Address abc() {
        return new Address("ADDR-020", "ABC portada", null,
                "https://www.abc.es/portada", AddressType.URL_NEWS)
                .withVerification(VerificationStatus.VERIFIED, "2026-11-21T00:00:00Z");
    }

    /** A scrape connector serving the mixed listing for any address. */
    private es.jllopez.neis.domain.port.ContentScrapePort mixedScrape() {
        return new es.jllopez.neis.domain.port.ContentScrapePort() {
            private es.jllopez.neis.domain.model.ScrapeResult mixed() {
                byte[] html = MIXED_LISTING.getBytes(StandardCharsets.UTF_8);
                return new es.jllopez.neis.domain.model.ScrapeResult(
                        MIXED_LISTING, "text/html; charset=UTF-8", html.length);
            }

            @Override
            public es.jllopez.neis.domain.model.ScrapeResult scrape(Address address) {
                return mixed();
            }

            @Override
            public es.jllopez.neis.domain.model.ScrapeResult scrape(Address address, int page) {
                return mixed();
            }
        };
    }

    private PreconfiguredSelectors abcSelector() {
        return new PreconfiguredSelectors(new PreconfiguredSelectorProperties(
                List.of(new PreconfiguredSelectorProperties.Entry(
                        "ADDR-020", "news", "ul.news-list li"))),
                new JsoupSelectorConnector());
    }

    private TaskWorkService standaloneService() {
        PipelineService pipeline = new PipelineService(mixedScrape(),
                new JsoupSimplifyConnector(), new JsoupSelectorConnector(),
                new SelectorStorePort() {
                    @Override
                    public Optional<StoredSelectors> load(String addressId) {
                        return Optional.empty();
                    }

                    @Override
                    public void save(StoredSelectors selectors) {
                        // not exercised here
                    }
                },
                new PaginationDetector(),
                new es.jllopez.neis.domain.service.ExposedFilterDetector(),
                noAi(), catalog(), new UrlSyntaxValidator(),
                abcSelector(), newsEventStore,
                new es.jllopez.neis.application.ContentStoreService(
                        new InMemoryContentStorePort(), workDir.toString()),
                new JsoupObstructionClearer(),
                new es.jllopez.neis.application.ClearingSelectors(
                        new es.jllopez.neis.application.ClearingProperties(List.of()),
                        new JsoupSelectorConnector()),
                new JsoupDomAnalyzer(), null, workDir.toString());
        return new TaskWorkService(pipeline, catalog(), newsEventStore,
                feedSource(MIXED_FEED),
                new es.jllopez.neis.infrastructure.adapter.catalog.WebpageTypeCatalog(
                        new es.jllopez.neis.infrastructure.adapter.catalog.WebpageTypeProperties()),
                new PreconfiguredFilters(new PreconfiguredFilterProperties(List.of())),
                new es.jllopez.neis.application.ClearingSelectors(
                        new es.jllopez.neis.application.ClearingProperties(List.of()),
                        new JsoupSelectorConnector()), null);
    }

    private AddressCatalogService catalog() {
        return catalogService;
    }

    private es.jllopez.neis.domain.port.FeedSourcePort feedSource(String document) {
        return new es.jllopez.neis.infrastructure.adapter.feed.RomeFeedConnector(5, 1048576) {
            @Override
            public byte[] fetch(Address address) {
                return document.getBytes(StandardCharsets.UTF_8);
            }
        };
    }

    private static org.springframework.beans.factory.ObjectProvider<es.jllopez.neis.domain.port.AiAssistantPort> noAi() {
        return new org.springframework.beans.factory.ObjectProvider<>() {
            @Override
            public es.jllopez.neis.domain.port.AiAssistantPort getObject() {
                throw new IllegalStateException("no AI in this test");
            }

            @Override
            public es.jllopez.neis.domain.port.AiAssistantPort getIfAvailable() {
                return null;
            }
        };
    }

    @Test
    void externalHostItemsAreDroppedAndSameHostKept() {
        String outcome = standaloneService().execute("WEBSCRAPE", catalog().byId("ADDR-020"));

        // the outcome carries the drop count (FR5)
        assertTrue(outcome.contains("2 external item(s) dropped (host mismatch)"), outcome);
        assertTrue(outcome.contains("items extracted: 2"), outcome);

        // only the same-host rows reach the store: the COPE partner
        // widget and the MujerHoy sister-brand card are gone
        var stored = newsEventStore.byAddress("ADDR-020");
        assertEquals(2, stored.size());
        assertTrue(stored.stream().allMatch(i -> i.url().startsWith("https://www.abc.es/")),
                stored.toString());
        assertFalse(stored.stream().anyMatch(i -> i.url().contains("cope.es")));
        assertFalse(stored.stream().anyMatch(i -> i.url().contains("mujerhoy.com")));
    }

    @Test
    void theFeedPathInheritsTheSameHostRule() {
        // FR3: a FEED address's parse applies the same filter in the
        // same place — an entry whose link host differs is dropped.
        catalogService.updateWebpageType("ADDR-021", "feed");
        PipelineService pipeline = new PipelineService(mixedScrape(),
                new JsoupSimplifyConnector(), new JsoupSelectorConnector(),
                new SelectorStorePort() {
                    @Override
                    public Optional<StoredSelectors> load(String addressId) {
                        return Optional.empty();
                    }

                    @Override
                    public void save(StoredSelectors selectors) {
                        // not exercised here
                    }
                },
                new PaginationDetector(),
                new es.jllopez.neis.domain.service.ExposedFilterDetector(),
                noAi(), catalogService, new UrlSyntaxValidator(),
                new PreconfiguredSelectors(new PreconfiguredSelectorProperties(List.of()),
                        new JsoupSelectorConnector()),
                newsEventStore,
                new es.jllopez.neis.application.ContentStoreService(
                        new InMemoryContentStorePort(), workDir.toString()),
                new JsoupObstructionClearer(),
                new es.jllopez.neis.application.ClearingSelectors(
                        new es.jllopez.neis.application.ClearingProperties(List.of()),
                        new JsoupSelectorConnector()),
                new JsoupDomAnalyzer(), null, workDir.toString());
        TaskWorkService feedService = new TaskWorkService(pipeline, catalogService,
                newsEventStore, feedSource(MIXED_FEED),
                new es.jllopez.neis.infrastructure.adapter.catalog.WebpageTypeCatalog(
                        new es.jllopez.neis.infrastructure.adapter.catalog.WebpageTypeProperties()),
                new PreconfiguredFilters(new PreconfiguredFilterProperties(List.of())),
                new es.jllopez.neis.application.ClearingSelectors(
                        new es.jllopez.neis.application.ClearingProperties(List.of()),
                        new JsoupSelectorConnector()), null);

        String outcome = feedService.execute("WEBSCRAPE", catalogService.byId("ADDR-021"));

        assertTrue(outcome.startsWith("WEBSCRAPE (feed):"), outcome);
        assertTrue(outcome.contains("1 external item(s) dropped (host mismatch)"), outcome);
        assertTrue(outcome.contains("1 item(s)"), outcome);
        var stored = newsEventStore.byAddress("ADDR-021");
        assertEquals(1, stored.size());
        assertEquals("https://www.abc.es/feed/propia-1", stored.get(0).url());
    }

    @Test
    void thePipelineRunPathCarriesTheDropCountAndReportLine() {
        // The FULL pipeline (plain extraction path, every retrieval
        // strategy shares it) reports the drop count on the result and
        // in the PRINT report; the store keeps same-host rows only.
        PipelineService pipeline = new PipelineService(mixedScrape(),
                new JsoupSimplifyConnector(), new JsoupSelectorConnector(),
                new SelectorStorePort() {
                    @Override
                    public Optional<StoredSelectors> load(String addressId) {
                        return Optional.empty();
                    }

                    @Override
                    public void save(StoredSelectors selectors) {
                        // not exercised here
                    }
                },
                new PaginationDetector(),
                new es.jllopez.neis.domain.service.ExposedFilterDetector(),
                noAi(), catalog(), new UrlSyntaxValidator(),
                abcSelector(), newsEventStore,
                new es.jllopez.neis.application.ContentStoreService(
                        new InMemoryContentStorePort(), workDir.toString()),
                new JsoupObstructionClearer(),
                new es.jllopez.neis.application.ClearingSelectors(
                        new es.jllopez.neis.application.ClearingProperties(List.of()),
                        new JsoupSelectorConnector()),
                new JsoupDomAnalyzer(), null, workDir.toString());

        PipelineResult result = pipeline.run(catalog().byId("ADDR-020"));

        assertEquals(2, result.externalDropped());
        assertEquals(2, result.items().size());
        var stored = newsEventStore.byAddress("ADDR-020");
        assertTrue(stored.stream().allMatch(i -> i.url().startsWith("https://www.abc.es/")));
        String report = PipelineReport.render("TEST", result);
        assertTrue(report.contains("2 external item(s) dropped (host mismatch, US-06.16)"),
                report);
    }

    @Test
    void theDecisionShapesArePinnedOnTheFilter() {
        // The MujerHoy shape (sister brand, different registered
        // domain) is DROPPED — the decision regression guard.
        Address abcAddress = abc();
        var mujerHoy = item("https://www.mujerhoy.com/vida/x");
        assertEquals(1, SameHostFilter.apply(abcAddress, List.of(mujerHoy)).dropped());

        // abc.es/contentfactory sponsored cards are same-host: KEPT.
        var contentFactory = item("https://www.abc.es/contentfactory/post/x");
        assertEquals(0, SameHostFilter.apply(abcAddress, List.of(contentFactory)).dropped());
        assertEquals(1, SameHostFilter.apply(abcAddress,
                List.of(contentFactory)).kept().size());

        // host comparison is case-insensitive and ignores an explicit
        // scheme-default port
        assertEquals(0, SameHostFilter.apply(abcAddress,
                List.of(item("https://WWW.ABC.ES/local/y"))).dropped());
        assertEquals(0, SameHostFilter.apply(abcAddress,
                List.of(item("https://www.abc.es:443/local/y"))).dropped());

        // a URL-less item cannot be host-judged: it is KEPT (US-14.4)
        var urlLess = new NewsEventItem("ADDR-020", "news", "Sin enlace",
                "d", null, "2026-11-20");
        var r = SameHostFilter.apply(abcAddress, List.of(urlLess));
        assertEquals(0, r.dropped());
        assertEquals(1, r.kept().size());
    }

    private static NewsEventItem item(String url) {
        return new NewsEventItem("ADDR-020", "news", "Un titular cualquiera",
                "d", url, "2026-11-20");
    }
}
