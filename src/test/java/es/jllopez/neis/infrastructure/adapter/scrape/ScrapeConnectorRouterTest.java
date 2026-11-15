package es.jllopez.neis.infrastructure.adapter.scrape;

import es.jllopez.neis.domain.model.Address;
import es.jllopez.neis.domain.model.ScrapeResult;
import es.jllopez.neis.domain.port.WebpageTypeCatalogPort;
import es.jllopez.neis.infrastructure.adapter.catalog.WebpageTypeCatalog;
import es.jllopez.neis.infrastructure.adapter.catalog.WebpageTypeProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The SCRAPE routing connector: configuration picks static fixtures
 * (default) or live per-type retrieval (US-06.5, issue #71) — the
 * address's classified webpage type selects HTTP or browser
 * rendering; the per-run {@code connector} parameter overrides both;
 * unknown values fail loud instead of silently downgrading.
 */
class ScrapeConnectorRouterTest {

    private static final Address UNCLASSIFIED = new Address("ADDR-001", "Seed", null,
            "https://example.invalid/list", es.jllopez.neis.domain.model.AddressType.URL_NEWS);

    private static final Address SPA_ANGULAR = UNCLASSIFIED.withWebpageType("spa-angular");
    private static final Address STATIC = UNCLASSIFIED.withWebpageType("static");

    @Test
    void staticModeServesTheFixtures() {
        ScrapeConnectorRouter router = new ScrapeConnectorRouter(
                new StaticScrapeConnector(), emptyProvider(), emptyRendered(),
                catalog(), "static");

        ScrapeResult result = router.scrape(SPA_ANGULAR);

        assertEquals("text/html; charset=UTF-8", result.contentType());
        assertTrue(result.html().contains("<"), "fixture HTML body");
    }

    @Test
    void liveModeRoutesHttpTypesToTheHttpConnector() {
        ScrapeConnectorRouter router = new ScrapeConnectorRouter(
                new StaticScrapeConnector(), singleProvider(httpStub("static-page")),
                emptyRendered(), catalog(), "live");

        assertEquals("static-page", router.scrape(STATIC).html());
    }

    @Test
    void liveModeRoutesRenderedTypesToThePlaywrightConnector() {
        ScrapeConnectorRouter router = new ScrapeConnectorRouter(
                new StaticScrapeConnector(), singleProvider(httpStub("http-page")),
                singleRendered(renderedStub("rendered-page")), catalog(), "live");

        assertEquals("rendered-page", router.scrape(SPA_ANGULAR).html());
    }

    @Test
    void liveModeFallsBackToHttpForAnUnclassifiedAddress() {
        ScrapeConnectorRouter router = new ScrapeConnectorRouter(
                new StaticScrapeConnector(), singleProvider(httpStub("http-page")),
                emptyRendered(), catalog(), "live");

        assertEquals("http-page", router.scrape(UNCLASSIFIED).html());
    }

    @Test
    void liveModeFailsLoudOnAnUnknownWebpageType() {
        ScrapeConnectorRouter router = new ScrapeConnectorRouter(
                new StaticScrapeConnector(), emptyProvider(), emptyRendered(),
                catalog(), "live");

        assertThrows(IllegalStateException.class,
                () -> router.scrape(UNCLASSIFIED.withWebpageType("spa-svelte")));
    }

    @Test
    void perRunOverridePinsTheStrategyRegardlessOfType() {
        ScrapeConnectorRouter router = new ScrapeConnectorRouter(
                new StaticScrapeConnector(), singleProvider(httpStub("http-page")),
                singleRendered(renderedStub("rendered-page")), catalog(), "live");

        assertEquals("rendered-page", router.scrape(STATIC, "rendered").html(),
                "a static-type address fetches rendered when the run pins it");
        assertEquals("http-page", router.scrape(SPA_ANGULAR, "http").html(),
                "an SPA address fetches over HTTP when the run pins it");
        assertEquals("text/html; charset=UTF-8",
                router.scrape(SPA_ANGULAR, "fixtures").contentType(),
                "the fixtures stay reachable per run for offline checks");
    }

    @Test
    void unknownPerRunOverrideFailsTheRun() {
        ScrapeConnectorRouter router = new ScrapeConnectorRouter(
                new StaticScrapeConnector(), emptyProvider(), emptyRendered(),
                catalog(), "live");

        assertThrows(IllegalArgumentException.class,
                () -> router.scrape(SPA_ANGULAR, "selenium"));
    }

    @Test
    void defaultModeIsStatic() {
        ScrapeConnectorRouter router = new ScrapeConnectorRouter(
                new StaticScrapeConnector(), emptyProvider(), emptyRendered(),
                catalog(), null);

        assertEquals("text/html; charset=UTF-8", router.scrape(SPA_ANGULAR).contentType());
    }

    @Test
    void unknownModeFailsTheBoot() {
        assertThrows(IllegalArgumentException.class, () -> new ScrapeConnectorRouter(
                new StaticScrapeConnector(), emptyProvider(), emptyRendered(),
                catalog(), "http"));
    }

    // ===== US-06.6 review queue: the filtered path honors the
    // connector override, and the URL query-parameter edges are exact =====

    @Test
    void filteredPathForcesTheHttpStrategyWhenTheRunPinsIt() {
        ScrapeConnectorRouter router = new ScrapeConnectorRouter(
                new StaticScrapeConnector(),
                singleProvider(httpUrlStub("http://fetched/with/filters")),
                singleRendered(renderedStub("rendered-page")), catalog(), "live");

        ScrapeResult result = router.scrapeWithFilters(SPA_ANGULAR,
                java.util.Map.of("programme", "EU4Health"), "http");

        assertEquals("http://fetched/with/filters", result.html(),
                "an SPA address fetches over HTTP when the run pins it");
    }

    @Test
    void filteredPathForcesTheRenderedStrategyWhenTheRunPinsIt() {
        ScrapeConnectorRouter router = new ScrapeConnectorRouter(
                new StaticScrapeConnector(), singleProvider(httpStub("http-page")),
                singleRendered(renderedFilterStub("rendered-filtered")), catalog(), "live");

        assertEquals("rendered-filtered", router.scrapeWithFilters(STATIC,
                        java.util.Map.of("programme", "EU4Health"), "rendered").html(),
                "a static-type address renders when the run pins it");
    }

    @Test
    void unknownOverrideOnTheFilteredPathFailsTheRun() {
        ScrapeConnectorRouter router = new ScrapeConnectorRouter(
                new StaticScrapeConnector(), emptyProvider(), emptyRendered(),
                catalog(), "live");

        assertThrows(IllegalArgumentException.class,
                () -> router.scrapeWithFilters(SPA_ANGULAR,
                        java.util.Map.of("programme", "EU4Health"), "selenium"));
    }

    @Test
    void withQueryParamsInsertsTheParametersBeforeTheFragment() {
        assertEquals("https://x.eu/list?programme=EU4Health#section",
                ScrapeConnectorRouter.withQueryParams("https://x.eu/list#section",
                        java.util.Map.of("programme", "EU4Health")));
    }

    @Test
    void withQueryParamsAppendsToAnExistingQueryOfOtherKeys() {
        assertEquals("https://x.eu/list?page=1&programme=EU4Health",
                ScrapeConnectorRouter.withQueryParams("https://x.eu/list?page=1",
                        java.util.Map.of("programme", "EU4Health")));
    }

    @Test
    void withQueryParamsRefusesAKeyTheUrlAlreadyCarries() {
        // A duplicated query parameter would make the server-side
        // filter ambiguous — loud, not silent.
        assertThrows(IllegalArgumentException.class,
                () -> ScrapeConnectorRouter.withQueryParams(
                        "https://x.eu/list?programme=EU",
                        java.util.Map.of("programme", "EU4Health")));
        assertThrows(IllegalArgumentException.class,
                () -> ScrapeConnectorRouter.withQueryParams(
                        "https://x.eu/list?filter+name=x",
                        java.util.Map.of("filter name", "y")));
    }

    @Test
    void withQueryParamsEncodesNamesAndValues() {
        assertEquals("https://x.eu/list?filter+name=a+b",
                ScrapeConnectorRouter.withQueryParams("https://x.eu/list",
                        java.util.Map.of("filter name", "a b")));
    }

    @Test
    void staticModeFilteredPathStillForcesTheOverride() {
        // Blocking review finding on the queue-fix commit: static mode
        // used to short-circuit to the fixtures BEFORE the override was
        // read — connector=http + filters silently served fixtures, and
        // an unknown override never failed loud. The override wins in
        // every mode, the same rule as the unfiltered path.
        ScrapeConnectorRouter router = new ScrapeConnectorRouter(
                staticFilterStub(),
                singleProvider(httpUrlStub("http://fetched/with/filters")),
                singleRendered(renderedFilterStub("rendered-filtered")),
                catalog(), "static");

        assertEquals("http://fetched/with/filters", router.scrapeWithFilters(SPA_ANGULAR,
                        java.util.Map.of("programme", "EU4Health"), "http").html(),
                "static mode honors the http override — no silent fixtures");
        assertEquals("rendered-filtered", router.scrapeWithFilters(SPA_ANGULAR,
                        java.util.Map.of("programme", "EU4Health"), "rendered").html(),
                "static mode honors the rendered override");
    }

    @Test
    void staticModeFilteredPathFailsLoudOnAnUnknownOverride() {
        ScrapeConnectorRouter router = new ScrapeConnectorRouter(
                new StaticScrapeConnector(), emptyProvider(), emptyRendered(),
                catalog(), "static");

        assertThrows(IllegalArgumentException.class,
                () -> router.scrapeWithFilters(SPA_ANGULAR,
                        java.util.Map.of("programme", "EU4Health"), "selenium"));
    }

    @Test
    void staticModeFilteredPathDefaultsToTheFixtures() {
        ScrapeConnectorRouter router = new ScrapeConnectorRouter(
                staticFilterStub(), emptyProvider(), emptyRendered(), catalog(), "static");

        assertEquals("static-filtered", router.scrapeWithFilters(SPA_ANGULAR,
                        java.util.Map.of("programme", "EU4Health"), null).html(),
                "no override in static mode is the offline filtered capture");
    }

    @Test
    void withQueryParamsRefusesAPercentEncodedDuplicateKey() {
        assertThrows(IllegalArgumentException.class,
                () -> ScrapeConnectorRouter.withQueryParams(
                        "https://x.eu/list?filter%20name=x",
                        java.util.Map.of("filter name", "y")));
    }

    private static WebpageTypeCatalogPort catalog() {
        return new WebpageTypeCatalog(new WebpageTypeProperties());
    }

    private HttpScrapeConnector httpStub(String body) {
        return new HttpScrapeConnector(5, 1024 * 1024) {
            @Override
            public ScrapeResult scrape(Address address) {
                return new ScrapeResult(body, "text/html", body.length());
            }
        };
    }

    private HttpScrapeConnector httpUrlStub(String body) {
        return new HttpScrapeConnector(5, 1024 * 1024) {
            @Override
            public ScrapeResult scrapeUrl(String url) {
                return new ScrapeResult(body, "text/html", body.length());
            }
        };
    }

    private PlaywrightRenderConnector renderedFilterStub(String body) {
        return new PlaywrightRenderConnector(1024 * 1024, 5) {
            @Override
            public ScrapeResult scrapeWithFilters(Address address,
                    java.util.Map<String, String> filters, String connectorOverride) {
                return new ScrapeResult(body, "text/html", body.length());
            }
        };
    }

    private StaticScrapeConnector staticFilterStub() {
        return new StaticScrapeConnector() {
            @Override
            public ScrapeResult scrapeWithFilters(Address address,
                    java.util.Map<String, String> filters, String connectorOverride) {
                return new ScrapeResult("static-filtered", "text/html", 15);
            }
        };
    }

    private PlaywrightRenderConnector renderedStub(String body) {
        return new PlaywrightRenderConnector(1024 * 1024, 5) {
            @Override
            public ScrapeResult scrape(Address address) {
                return new ScrapeResult(body, "text/html", body.length());
            }
        };
    }

    private ObjectProvider<HttpScrapeConnector> emptyProvider() {
        return new ObjectProvider<>() {
            @Override
            public HttpScrapeConnector getObject() {
                throw new IllegalStateException("no live connector in this test");
            }

            @Override
            public HttpScrapeConnector getIfAvailable() {
                return null;
            }
        };
    }

    private ObjectProvider<HttpScrapeConnector> singleProvider(HttpScrapeConnector connector) {
        return new ObjectProvider<>() {
            @Override
            public HttpScrapeConnector getObject() {
                return connector;
            }

            @Override
            public HttpScrapeConnector getIfAvailable() {
                return connector;
            }
        };
    }

    private ObjectProvider<PlaywrightRenderConnector> emptyRendered() {
        return new ObjectProvider<>() {
            @Override
            public PlaywrightRenderConnector getObject() {
                throw new IllegalStateException("no rendered connector in this test");
            }

            @Override
            public PlaywrightRenderConnector getIfAvailable() {
                return null;
            }
        };
    }

    private ObjectProvider<PlaywrightRenderConnector> singleRendered(
            PlaywrightRenderConnector connector) {
        return new ObjectProvider<>() {
            @Override
            public PlaywrightRenderConnector getObject() {
                return connector;
            }

            @Override
            public PlaywrightRenderConnector getIfAvailable() {
                return connector;
            }
        };
    }
}
