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
            new InMemoryAddressCatalogPort(List.of(
                    new Address("ADDR-001", "News",
                            null, "https://commission.europa.eu/news-and-media/news_en",
                            AddressType.URL_NEWS),
                    // US-06.6: the fixture address whose captured
                    // filtered response embodies programme=EU4Health.
                    new Address("ADDR-006", "News filtered",
                            null, "https://example.eu/filtered-news",
                            AddressType.URL_NEWS))),
            new UrlSyntaxValidator());

    @TempDir
    Path workDir;

    private final es.jllopez.neis.testsupport.InMemoryNewsEventStorePort newsEventStore =
            new es.jllopez.neis.testsupport.InMemoryNewsEventStorePort();

    /** A feed source whose fetch serves the given document. */
    private es.jllopez.neis.domain.port.FeedSourcePort feedSource() {
        return feedSource(FEED_DOC.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private es.jllopez.neis.domain.port.FeedSourcePort feedSource(byte[] document) {
        return new es.jllopez.neis.infrastructure.adapter.feed.RomeFeedConnector(5, 1048576) {
            @Override
            public byte[] fetch(Address address) {
                return document;
            }
        };
    }

    private es.jllopez.neis.domain.port.WebpageTypeCatalogPort webpageTypes() {
        return new es.jllopez.neis.infrastructure.adapter.catalog.WebpageTypeCatalog(
                new es.jllopez.neis.infrastructure.adapter.catalog.WebpageTypeProperties());
    }

    private static final String FEED_DOC = """
            <?xml version="1.0" encoding="UTF-8"?>
            <rss version="2.0"><channel>
              <title>News feed</title>
              <item><title>Feed item one</title>
                <description>First item description</description>
                <link>https://example.eu/feed/1</link>
                <pubDate>Tue, 10 Nov 2026 10:00:00 GMT</pubDate></item>
              <item><title>Feed item two</title>
                <description>Second item description</description>
                <link>https://example.eu/feed/2</link>
                <pubDate>Wed, 11 Nov 2026 10:00:00 GMT</pubDate></item>
            </channel></rss>
            """;

    private TaskWorkService service(PreconfiguredSelectors preconfigured) {
        return service(preconfigured, null);
    }

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
        return new TaskWorkService(pipeline, catalog, newsEventStore,
                feedSource(), webpageTypes(), new PreconfiguredFilters(new PreconfiguredFilterProperties(List.of())), configuredMaxItems);
    }

    private TaskWorkService serviceWithFeedSource(PreconfiguredSelectors preconfigured,
                                                  es.jllopez.neis.domain.port.FeedSourcePort source) {
        return serviceWithFilters(preconfigured, source,
                new PreconfiguredFilters(new PreconfiguredFilterProperties(List.of())));
    }

    private TaskWorkService serviceWithFilters(PreconfiguredSelectors preconfigured,
                                               es.jllopez.neis.domain.port.FeedSourcePort source,
                                               PreconfiguredFilters filters) {
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
        return new TaskWorkService(pipeline, catalog, newsEventStore,
                source, webpageTypes(), filters, null);
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

            String outcome = new TaskWorkService(livePipeline, liveCatalog, newsEventStore,
                        feedSource(), webpageTypes(),
                        new PreconfiguredFilters(new PreconfiguredFilterProperties(List.of())), null)
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

            String outcome = new TaskWorkService(livePipeline, liveCatalog, newsEventStore,
                        feedSource(), webpageTypes(),
                        new PreconfiguredFilters(new PreconfiguredFilterProperties(List.of())), null)
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

    @Test
    void feedSourceIsParsedDirectlyWithoutAnyScraping() {
        // US-06.8 (issue #86): a feed-classified address goes through
        // the feed path — the document is fetched and parsed with Rome,
        // no selector and no HTML scraping when the feed suffices. The
        // records carry the same repository persistence and provenance.
        catalog.updateWebpageType("ADDR-001", "feed");
        // A selector override would silently do nothing on a feed:
        // refused loud.
        IllegalArgumentException refused = assertThrows(
                IllegalArgumentException.class, () -> service(null).execute(
                        "WEBSCRAPE", catalog.byId("ADDR-001"),
                        java.util.Map.of("selector", "ul li"), null, null, "EXEC-feed-1"));
        assertTrue(refused.getMessage().contains("does not apply to feed sources"),
                refused.getMessage());

        String outcome = service(null).execute("WEBSCRAPE",
                catalog.byId("ADDR-001"), java.util.Map.of(), null, null, "EXEC-feed-1");

        assertTrue(outcome.startsWith("WEBSCRAPE (feed): 2 item(s)"), outcome);
        assertTrue(outcome.contains("; no cap"), outcome);
        // The records landed in the repository with feed provenance.
        var stored = newsEventStore.byAddress("ADDR-001");
        assertEquals(2, stored.size());
        assertEquals("Feed item one", stored.get(0).title());
        assertEquals("https://example.eu/feed/1", stored.get(0).url());
        assertEquals("2026-11-10", stored.get(0).date(), "pubDate mapped to ISO");
        assertEquals("EXEC-feed-1", stored.get(0).executionId());
        // The feed document is the provenance content: items.json and
        // feed.xml artifacts exist under the capture folder.
        assertTrue(Files.exists(firstFileMatching("feed.xml")), "feed.xml artifact");
        assertTrue(Files.exists(firstFileMatching("items.json")), "items.json artifact");
    }

    @Test
    void feedCapStopsTheExtraction() {
        catalog.updateWebpageType("ADDR-001", "feed");

        String outcome = service(null).execute("WEBSCRAPE",
                catalog.byId("ADDR-001"),
                java.util.Map.of("max-items", "1"), null, null, "EXEC-feed-2");

        assertTrue(outcome.startsWith("WEBSCRAPE (feed): 1 item(s)"), outcome);
        assertTrue(outcome.contains("cap 1"), outcome);
        assertEquals(1, newsEventStore.byAddress("ADDR-001").size());
    }

    @Test
    void nonUtf8FeedIsDecodedPerItsXmlDeclaration() {
        // Review finding (2026-11-15): decoding the document
        // unconditionally as UTF-8 corrupted any non-UTF-8 feed before
        // Rome saw it (mojibake persisted, no error). The bytes must
        // travel verbatim to the parse; the XML declaration's charset
        // decides. The title contains 'ã' (U+00E3) — mojibake would
        // surface as U+FFFD.
        String latin1Doc = """
                <?xml version="1.0" encoding="ISO-8859-1"?>
                <rss version="2.0"><channel>
                  <title>News feed</title>
                  <item><title>Aministra\u00e7\u00e3o p\u00fablica aprova plano</title>
                    <description>Conte\u00fado com acentua\u00e7\u00e3o.</description>
                    <link>https://example.eu/feed/1</link>
                    <pubDate>Tue, 10 Nov 2026 10:00:00 GMT</pubDate></item>
                </channel></rss>
                """;
        catalog.updateWebpageType("ADDR-001", "feed");

        String outcome = serviceWithFeedSource(catalog(List.of()),
                feedSource(latin1Doc.getBytes(
                        java.nio.charset.StandardCharsets.ISO_8859_1)))
                .execute("WEBSCRAPE", catalog.byId("ADDR-001"), java.util.Map.of(),
                        null, null, "EXEC-feed-l1");

        assertTrue(outcome.startsWith("WEBSCRAPE (feed): 1 item(s)"), outcome);
        var stored = newsEventStore.byAddress("ADDR-001");
        assertEquals(1, stored.size());
        assertEquals("Aministração pública aprova plano", stored.get(0).title());
        assertFalse(stored.get(0).title().contains("\uFFFD"),
                "replacement character means the bytes were double-mangled");
        // The artifact holds the document decoded per its declaration
        // (storage is UTF-8).
        try {
            String artifact = Files.readString(firstFileMatching("feed.xml"));
            assertTrue(artifact.contains("Aministração pública"), artifact);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void unclassifiedAddressNeverTakesTheFeedPath() {
        // No webpage type: the ordinary scrape path runs (and skips
        // extraction loudly at the selector, never silently
        // feed-parsing).
        String outcome = service(catalog(List.of())).execute("WEBSCRAPE",
                catalog.byId("ADDR-001"));

        assertTrue(outcome.contains("no preconfigured selector"), outcome);
        assertFalse(Files.exists(firstFileMatching("feed.xml")),
                "no feed path for an unclassified address");
    }

    // ===== US-06.6 (issue #73): filters and values in WEBSCRAPE =====

    @Test
    void webScrapeAppliesFiltersViaQueryParameters() {
        // Static page (US-06.6): the filter set is applied BEFORE
        // extraction (URL query parameters for static pages) and the
        // applied set is recorded with the capture metadata; the
        // outcome line names it.
        String outcome = serviceWithFeedSource(catalog(List.of()), feedSource())
                .execute("WEBSCRAPE", catalog.byId("ADDR-006"),
                        java.util.Map.of("filter.programme", "EU4Health"),
                        null, null, "EXEC-f1");

        assertTrue(outcome.contains("; filters: programme=EU4Health;"), outcome);
        assertTrue(outcome.contains("paginated:"), outcome);
    }

    @Test
    void aFilterTheSourceCannotServeIsRefusedLoud() {
        // The fixtures embody exactly one filtered response per
        // address: a filter set they do not embody is refused loud, no
        // silently unfiltered content is served (US-06.6).
        UnsupportedOperationException e = assertThrows(
                UnsupportedOperationException.class,
                () -> serviceWithFeedSource(catalog(List.of()), feedSource()).execute(
                        "WEBSCRAPE", catalog.byId("ADDR-006"),
                        java.util.Map.of("filter.category", "human"),
                        null, null, null));
        assertTrue(e.getMessage().contains("does not match"), e.getMessage());
    }

    @Test
    void filterValuesAreValidatedBeforeAnythingRuns() {
        // US-12.3 rule applied to filters: a blank filter value is
        // refused, never applied.
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> serviceWithFeedSource(catalog(List.of()), feedSource()).execute(
                        "WEBSCRAPE", catalog.byId("ADDR-006"),
                        java.util.Map.of("filter.programme", " "),
                        null, null, null));
        assertTrue(e.getMessage().contains("empty value"), e.getMessage());
    }

    @Test
    void filtersAreRefusedOnFeeds() {
        // A feed is parsed directly (US-06.8): filters do not apply —
        // refused loud.
        catalog.updateWebpageType("ADDR-001", "feed");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service(null).execute("WEBSCRAPE", catalog.byId("ADDR-001"),
                        java.util.Map.of("filter.category", "human"),
                        null, null, null));
        assertTrue(e.getMessage().contains("does not apply to feed sources"), e.getMessage());
    }

    @Test
    void caseDifferingFilterParametersAreRefusedAsDuplicates() {
        // US-06.6 review finding: the duplicate check was DEAD — two
        // per-run parameters necessarily carry different map keys, so
        // only a case difference can collide and putIfAbsent is
        // case-sensitive. Detection is now case-insensitive (the
        // applied name keeps its original case).
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> serviceWithFeedSource(catalog(List.of()), feedSource()).execute(
                        "WEBSCRAPE", catalog.byId("ADDR-006"),
                        java.util.Map.ofEntries(
                                java.util.Map.entry("filter.programme", "EU4Health"),
                                java.util.Map.entry("filter.Programme", "Horizon")),
                        null, null, null));
        assertTrue(e.getMessage().contains("Duplicate filter"), e.getMessage());
    }

    @Test
    void preconfiguredFiltersAreRefusedOnFeeds() {
        // US-06.6 review finding: a feed address's preconfigured set
        // was silently ignored (dead configuration, the run proceeded
        // unfiltered) — refused loud, the same rule as per-run filters.
        catalog.updateWebpageType("ADDR-001", "feed");
        PreconfiguredFilters filters = new PreconfiguredFilters(
                new PreconfiguredFilterProperties(List.of(
                        new PreconfiguredFilterProperties.Entry(
                                "ADDR-001", "programme", "EU4Health"))));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> serviceWithFilters(catalog(List.of()), feedSource(), filters)
                        .execute("WEBSCRAPE", catalog.byId("ADDR-001"),
                                java.util.Map.of(), null, null, null));
        assertTrue(e.getMessage().contains("preconfigured WEBSCRAPE filters"),
                e.getMessage());
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
