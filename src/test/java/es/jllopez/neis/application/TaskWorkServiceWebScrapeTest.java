package es.jllopez.neis.application;

import es.jllopez.neis.domain.model.Address;
import es.jllopez.neis.domain.model.AddressType;
import es.jllopez.neis.domain.model.StoredSelectors;
import es.jllopez.neis.domain.port.SelectorStorePort;
import es.jllopez.neis.domain.service.PaginationDetector;
import es.jllopez.neis.domain.service.UrlSyntaxValidator;
import es.jllopez.neis.infrastructure.adapter.scrape.JsoupSelectorConnector;
import es.jllopez.neis.infrastructure.adapter.scrape.JsoupSimplifyConnector;
import es.jllopez.neis.infrastructure.adapter.scrape.StaticScrapeConnector;
import es.jllopez.neis.testsupport.InMemoryAddressCatalogPort;
import es.jllopez.neis.testsupport.InMemoryContentStorePort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WEBSCRAPE item extraction (US-06.4, issue #44): with a preconfigured
 * selector the step extracts the listing's news/events items with their
 * metadata into an items.json artifact — deterministic, no AI; without
 * one, extraction is skipped with an explicit note (identifying a
 * selector is ANALYZE's job).
 */
class TaskWorkServiceWebScrapeTest {

    private final AddressCatalogService catalog = new AddressCatalogService(
            new InMemoryAddressCatalogPort(List.of(new Address("ADDR-001", "News",
                    null, "https://commission.europa.eu/news-and-media/news_en",
                    AddressType.URL_NEWS))),
            new UrlSyntaxValidator());

    @TempDir
    Path workDir;

    private TaskWorkService service(PreconfiguredSelectors preconfigured) {
        return service(preconfigured, null);
    }

    private final es.jllopez.neis.testsupport.InMemoryNewsEventStorePort newsEventStore =
            new es.jllopez.neis.testsupport.InMemoryNewsEventStorePort();

    private TaskWorkService service(PreconfiguredSelectors preconfigured,
                                    String configuredMaxItems) {
        PipelineService pipeline = new PipelineService(new StaticScrapeConnector(),
                new JsoupSimplifyConnector(), new JsoupSelectorConnector(),
                new SelectorStorePort() {
                    @Override
                    public Optional<StoredSelectors> load(String addressId) {
                        return Optional.empty();
                    }

                    @Override
                    public void save(StoredSelectors selectors) {
                        // not exercised by the WEBSCRAPE step
                    }
                },
                new PaginationDetector(),
                new es.jllopez.neis.domain.service.ExposedFilterDetector(),
                new ObjectProviderStub(), catalog, new UrlSyntaxValidator(),
                preconfigured, newsEventStore,
                new ContentStoreService(new InMemoryContentStorePort(), workDir.toString()),
                workDir.toString());
        return new TaskWorkService(pipeline, catalog, newsEventStore, configuredMaxItems);
    }

    private PreconfiguredSelectors catalog(
            List<PreconfiguredSelectorProperties.Entry> entries) {
        return new PreconfiguredSelectors(new PreconfiguredSelectorProperties(entries),
                new JsoupSelectorConnector());
    }

    @BeforeEach
    void cleanWorkDir() throws Exception {
        // content folders are deterministic per URL; start empty
        if (Files.exists(workDir)) {
            try (var paths = Files.list(workDir)) {
                for (Path p : paths.toList()) {
                    if (Files.isDirectory(p)) {
                        try (var walk = Files.walk(p)) {
                            walk.sorted(java.util.Comparator.reverseOrder())
                                    .forEach(q -> q.toFile().delete());
                        }
                    } else {
                        Files.delete(p);
                    }
                }
            }
        }
    }

    @Test
    void extractsItemsWithAPreconfiguredSelector() {
        PreconfiguredSelectors configured = catalog(List.of(
                        new PreconfiguredSelectorProperties.Entry("ADDR-001", "news",
                                "ul.news-list li")));

        String outcome = service(configured).execute("WEBSCRAPE",
                catalog.byId("ADDR-001"));

        assertTrue(outcome.contains("items extracted: "), outcome);
        int count = Integer.parseInt(outcome.replaceAll(
                ".*items extracted: (\\d+).*", "$1"));
        assertTrue(count > 0, "the ADDR-001 fixture yields items: " + outcome);
        // The items.json artifact exists next to scrape.html and the
        // outcome names the selector.
        assertTrue(Files.exists(firstFileMatching("items.json")), outcome);
        assertTrue(outcome.contains("ul.news-list li"), outcome);
    }

    @Test
    void skipsExtractionWithoutAPreconfiguredSelector() {
        String outcome = service(catalog(List.of())).execute("WEBSCRAPE",
                catalog.byId("ADDR-001"));

        assertTrue(outcome.contains("no preconfigured selector"), outcome);
        assertFalse(Files.exists(firstFileMatching("items.json")),
                "no items.json without a selector");
    }

    @Test
    void extractedItemsCarryTheMetadata() throws Exception {
        PreconfiguredSelectors configured = catalog(List.of(
                        new PreconfiguredSelectorProperties.Entry("ADDR-001", "news",
                                "ul.news-list li")));
        service(configured).execute("WEBSCRAPE", catalog.byId("ADDR-001"));

        String json = Files.readString(firstFileMatching("items.json"));
        assertTrue(json.startsWith("[") && json.endsWith("]"), json);
        assertTrue(json.contains("\"title\""), json);
        assertTrue(json.contains("\"url\""), json);
        // Absolute detail URLs (base URI = the address URL).
        assertTrue(json.contains("https://commission.europa.eu/"), json);
    }

    @Test
    void maxItemsParameterCapsTheExtraction() {
        // US-12.5: extraction stops at the limit without error; the
        // outcome records how many were extracted and that the cap was
        // reached.
        PreconfiguredSelectors configured = catalog(List.of(
                        new PreconfiguredSelectorProperties.Entry("ADDR-001", "news",
                                "ul.news-list li")));

        String outcome = service(configured, null).execute("WEBSCRAPE",
                catalog.byId("ADDR-001"), java.util.Map.of("max-items", "3"));

        assertTrue(outcome.contains("items extracted: 3"), outcome);
        assertTrue(outcome.contains("cap 3"), outcome);
    }

    @Test
    void configuredMaxItemsIsTheDefaultCap() {
        // US-12.5: the externalized default applies when the task
        // carries no max-items parameter.
        PreconfiguredSelectors configured = catalog(List.of(
                        new PreconfiguredSelectorProperties.Entry("ADDR-001", "news",
                                "ul.news-list li")));

        String outcome = service(configured, "5").execute("WEBSCRAPE",
                catalog.byId("ADDR-001"));

        assertTrue(outcome.contains("items extracted: 5"), outcome);
        assertTrue(outcome.contains("cap 5"), outcome);
    }

    @Test
    void withoutCapConfigurationExtractionIsUnlimited() {
        PreconfiguredSelectors configured = catalog(List.of(
                        new PreconfiguredSelectorProperties.Entry("ADDR-001", "news",
                                "ul.news-list li")));

        String noParam = service(configured, null).execute("WEBSCRAPE",
                catalog.byId("ADDR-001"));
        String explicitHuge = service(configured, null).execute("WEBSCRAPE",
                catalog.byId("ADDR-001"), java.util.Map.of("max-items", "10000"));

        assertTrue(noParam.contains("no cap"), noParam);
        assertTrue(explicitHuge.contains("cap 10000"), explicitHuge);
        // The ADDR-001 fixture yields 15 items: the full listing is
        // extracted under both.
        int all = Integer.parseInt(noParam.replaceAll(".*items extracted: (\\d+).*", "$1"));
        int huge = Integer.parseInt(explicitHuge.replaceAll(".*items extracted: (\\d+).*", "$1"));
        assertEquals(all, huge);
        assertTrue(all > 5, "the uncapped listing exceeds the capped runs: " + all);
    }

    @Test
    void invalidConfiguredMaxItemsFailsTheBoot() {
        PreconfiguredSelectors configured = catalog(List.of(
                        new PreconfiguredSelectorProperties.Entry("ADDR-001", "news",
                                "ul.news-list li")));
        IllegalArgumentException letters = assertThrows(IllegalArgumentException.class,
                () -> service(configured, "many"));
        assertTrue(letters.getMessage().contains("must be an integer"));
        IllegalArgumentException zero = assertThrows(IllegalArgumentException.class,
                () -> service(configured, "0"));
        assertTrue(zero.getMessage().contains(">= 1"));
    }

    @Test
    void iterationFollowsTheNextPageLinkAndStopsWithoutOne() throws Exception {
        // US-12.4: WEBSCRAPE iterates page by page through the page's
        // own next-page control while it yields new items, then stops.
        // A live HTTP server (the HttpScrapeConnector fetches real
        // URLs): page 1 carries a rel=next to page 2, page 2 has none.
        com.sun.net.httpserver.HttpServer server =
                com.sun.net.httpserver.HttpServer.create(
                        new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        String page1 = "<html><body><ul class='news-list'>"
                + "<li><a href='/items/a1'>Item A1</a></li>"
                + "<li><a href='/items/a2'>Item A2</a></li></ul>"
                + "<nav class='pagination'><a rel='next' href='/page2'>Next</a></nav>"
                + "</body></html>";
        String page2 = "<html><body><ul class='news-list'>"
                + "<li><a href='/items/b1'>Item B1</a></li>"
                + "<li><a href='/items/b2'>Item B2</a></li></ul>"
                + "</body></html>";
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            byte[] body = (path.equals("/page2") ? page2 : page1)
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/html; charset=UTF-8");
            exchange.sendResponseHeaders(200, body.length);
            try (var out = exchange.getResponseBody()) {
                out.write(body);
            }
            exchange.close();
        });
        server.start();
        int port = server.getAddress().getPort();
        try {
            Address live = new Address("ADDR-LIVE", "Live listing", null,
                    "http://127.0.0.1:" + port + "/page1", AddressType.URL_NEWS);
            AddressCatalogService liveCatalog = new AddressCatalogService(
                    new InMemoryAddressCatalogPort(List.of(live)),
                    new UrlSyntaxValidator());
            PreconfiguredSelectors configured = new PreconfiguredSelectors(
                    new PreconfiguredSelectorProperties(List.of(
                            new PreconfiguredSelectorProperties.Entry("ADDR-LIVE", "news",
                                    "ul.news-list li"))),
                    new JsoupSelectorConnector());
            PipelineService livePipeline = new PipelineService(
                    new es.jllopez.neis.infrastructure.adapter.scrape.HttpScrapeConnector(5,
                            1024 * 1024),
                    new JsoupSimplifyConnector(), new JsoupSelectorConnector(),
                    new SelectorStorePort() {
                        @Override
                        public Optional<StoredSelectors> load(String addressId) {
                            return Optional.empty();
                        }

                        @Override
                        public void save(StoredSelectors selectors) {
                        }
                    },
                    new PaginationDetector(),
                    new es.jllopez.neis.domain.service.ExposedFilterDetector(),
                    new ObjectProviderStub(), liveCatalog, new UrlSyntaxValidator(),
                    configured, newsEventStore,
                    new ContentStoreService(new InMemoryContentStorePort(), workDir.toString()),
                    workDir.toString());

            String outcome = new TaskWorkService(livePipeline, liveCatalog, newsEventStore, null)
                    .execute("WEBSCRAPE", liveCatalog.byId("ADDR-LIVE"));

            assertTrue(outcome.contains("items extracted: 4"), outcome);
            assertTrue(outcome.contains("pages: 2"), outcome);
            assertTrue(outcome.contains("paginated: true"), outcome);
            String json = Files.readString(firstFileMatching("items.json"));
            assertTrue(json.contains("Item A1") && json.contains("Item B2"), json);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void capStopsTheIterationAcrossPages() throws Exception {
        // US-12.5 + US-12.4: the count accumulates across pages and
        // extraction stops at the limit (3 of 4 items over two pages).
        com.sun.net.httpserver.HttpServer server =
                com.sun.net.httpserver.HttpServer.create(
                        new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        String page1 = "<html><body><ul class='news-list'>"
                + "<li><a href='/items/a1'>Item A1</a></li>"
                + "<li><a href='/items/a2'>Item A2</a></li></ul>"
                + "<nav class='pagination'><a rel='next' href='/page2'>Next</a></nav>"
                + "</body></html>";
        String page2 = "<html><body><ul class='news-list'>"
                + "<li><a href='/items/b1'>Item B1</a></li>"
                + "<li><a href='/items/b2'>Item B2</a></li></ul>"
                + "</body></html>";
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            byte[] body = (path.equals("/page2") ? page2 : page1)
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/html; charset=UTF-8");
            exchange.sendResponseHeaders(200, body.length);
            try (var out = exchange.getResponseBody()) {
                out.write(body);
            }
            exchange.close();
        });
        server.start();
        int port = server.getAddress().getPort();
        try {
            Address live = new Address("ADDR-LIVE", "Live listing", null,
                    "http://127.0.0.1:" + port + "/page1", AddressType.URL_NEWS);
            AddressCatalogService liveCatalog = new AddressCatalogService(
                    new InMemoryAddressCatalogPort(List.of(live)),
                    new UrlSyntaxValidator());
            PreconfiguredSelectors configured = new PreconfiguredSelectors(
                    new PreconfiguredSelectorProperties(List.of(
                            new PreconfiguredSelectorProperties.Entry("ADDR-LIVE", "news",
                                    "ul.news-list li"))),
                    new JsoupSelectorConnector());
            PipelineService livePipeline = new PipelineService(
                    new es.jllopez.neis.infrastructure.adapter.scrape.HttpScrapeConnector(5,
                            1024 * 1024),
                    new JsoupSimplifyConnector(), new JsoupSelectorConnector(),
                    new SelectorStorePort() {
                        @Override
                        public Optional<StoredSelectors> load(String addressId) {
                            return Optional.empty();
                        }

                        @Override
                        public void save(StoredSelectors selectors) {
                        }
                    },
                    new PaginationDetector(),
                    new es.jllopez.neis.domain.service.ExposedFilterDetector(),
                    new ObjectProviderStub(), liveCatalog, new UrlSyntaxValidator(),
                    configured, newsEventStore,
                    new ContentStoreService(new InMemoryContentStorePort(), workDir.toString()),
                    workDir.toString());

            String outcome = new TaskWorkService(livePipeline, liveCatalog, newsEventStore, null)
                    .execute("WEBSCRAPE", liveCatalog.byId("ADDR-LIVE"),
                            java.util.Map.of("max-items", "3"));

            assertTrue(outcome.contains("items extracted: 3"), outcome);
            assertTrue(outcome.contains("cap 3"), outcome);
            String json = Files.readString(firstFileMatching("items.json"));
            assertFalse(json.contains("Item B2"), "extraction stopped at the cap: " + json);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void preconfiguredSelectorMatchingNothingAborts() {
        // US-12.3: the 0-match abort applies to preconfigured selectors
        // too — a scheduled run whose site markup drifts ends FAILED
        // with a clear error, not silently empty (US-12.3/12.6 review
        // finding, 2026-11-14).
        PreconfiguredSelectors configured = catalog(List.of(
                new PreconfiguredSelectorProperties.Entry("ADDR-001", "news",
                        ".site-redesigned-away")));

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service(configured, null).execute("WEBSCRAPE",
                        catalog.byId("ADDR-001")));
        assertTrue(e.getMessage().contains("matched no elements"), e.getMessage());
    }

    @Test
    void selectorOverrideReplacesThePreconfiguredSelector() {
        // US-12.6: the override replaces the preconfigured selector (the
        // preconfigured one deliberately does not match here) and the
        // outcome names it.
        PreconfiguredSelectors configured = catalog(List.of(
                new PreconfiguredSelectorProperties.Entry("ADDR-001", "news",
                        ".does-not-apply")));

        String outcome = service(configured, null).execute("WEBSCRAPE",
                catalog.byId("ADDR-001"), java.util.Map.of(
                        "selector", "ul.news-list li a", "max-items", "5"));

        assertTrue(outcome.contains("selector 'ul.news-list li a'"), outcome);
        assertTrue(outcome.contains("items extracted: 5"), outcome);
    }

    @Test
    void invalidSelectorOverrideAbortsLoudly() {
        // US-12.3 + US-12.6: an invalid override aborts the run with a
        // clear error — falling back is not silent.
        PreconfiguredSelectors configured = catalog(List.of(
                new PreconfiguredSelectorProperties.Entry("ADDR-001", "news",
                        "ul.news-list li")));

        IllegalArgumentException syntax = assertThrows(IllegalArgumentException.class,
                () -> service(configured, null).execute("WEBSCRAPE",
                        catalog.byId("ADDR-001"),
                        java.util.Map.of("selector", "###bad")));
        assertTrue(syntax.getMessage().contains("does not parse"), syntax.getMessage());
        assertTrue(syntax.getMessage().contains("(override)"), syntax.getMessage());

        // A syntactically valid override matching nothing on the page is
        // equally invalid here (US-12.3: selects at least one element).
        IllegalArgumentException empty = assertThrows(IllegalArgumentException.class,
                () -> service(configured, null).execute("WEBSCRAPE",
                        catalog.byId("ADDR-001"),
                        java.util.Map.of("selector", ".no-such-element")));
        assertTrue(empty.getMessage().contains("matched no elements"),
                empty.getMessage());
    }

    @Test
    void duplicateParamKeysAreRefused() {
        // US-12.3/12.6 review finding: duplicate --param keys would
        // silently take the last value — refused.
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> es.jllopez.neis.infrastructure.adapter.shell.ExecutionCommands
                        .parseParams(List.of("max-items=3", "max-items=5")));
        assertTrue(e.getMessage().contains("Duplicate"), e.getMessage());
    }

    private Path firstFileMatching(String fileName) {
        try (var walk = Files.walk(workDir)) {
            return walk.filter(p -> p.getFileName().toString().equals(fileName))
                    .findFirst().orElse(workDir.resolve("missing-" + fileName));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** No AI: the WEBSCRAPE step never touches the assistant. */
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
}
