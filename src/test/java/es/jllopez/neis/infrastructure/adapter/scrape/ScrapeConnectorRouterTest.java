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
