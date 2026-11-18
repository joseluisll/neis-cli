package es.jllopez.neis.infrastructure.adapter.preflight;

import es.jllopez.neis.domain.model.Address;
import es.jllopez.neis.domain.model.AddressType;
import es.jllopez.neis.domain.model.ScrapeResult;
import es.jllopez.neis.domain.model.WebpageType;
import es.jllopez.neis.domain.port.ContentScrapePort;
import es.jllopez.neis.domain.port.WebpageTypeCatalogPort;
import es.jllopez.neis.infrastructure.adapter.catalog.WebpageTypeCatalog;
import es.jllopez.neis.infrastructure.adapter.catalog.WebpageTypeProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The address preflight capture (US-13.6, issue #65): a lightweight
 * static fetch classifies the page through the deterministic
 * heuristics (US-06.5) and estimates the retrieval connector; an
 * application-shell static fetch triggers the rendered probe; the
 * probe record carries the full evidence for the capture metadata.
 */
class HttpClientAddressPreflightConnectorTest {

    private static final String STATIC_HTML = """
            <html><head><title>News</title></head><body>
            <ul class="news-list"><li>Item one</li></ul>
            </body></html>
            """;

    private static final String ANGULAR_SHELL = """
            <!doctype html><html><head>
            <meta ng-version="17.0.0"></head>
            <body><app-root></app-root></body></html>
            """;

    private static final String RENDERED_HTML = """
            <html><head><meta ng-version="17.0.0"></head><body>
            <app-root><ul class="news-list">
            <li>Rendered item one</li><li>Rendered item two</li></ul></app-root>
            </body></html>
            """;

    /** A scrape connector serving one fixture regardless of address. */
    private ContentScrapePort serving(String html, String contentType) {
        return new ContentScrapePort() {
            @Override
            public ScrapeResult scrape(Address address) {
                return new ScrapeResult(html, contentType, html.length());
            }
        };
    }

    @SuppressWarnings("unchecked")
    private ObjectProvider<es.jllopez.neis.infrastructure.adapter.scrape.PlaywrightRenderConnector>
    noRenderedConnector() {
        return new ObjectProvider<>() {
            @Override
            public es.jllopez.neis.infrastructure.adapter.scrape.PlaywrightRenderConnector getObject() {
                throw new IllegalStateException("no Playwright in this test");
            }

            @Override
            public es.jllopez.neis.infrastructure.adapter.scrape.PlaywrightRenderConnector getIfAvailable() {
                return null;
            }
        };
    }

    private HttpClientAddressPreflightConnector connector(
            ContentScrapePort scrape,
            ObjectProvider<es.jllopez.neis.infrastructure.adapter.scrape.PlaywrightRenderConnector> rendered) {
        return new HttpClientAddressPreflightConnector(scrape, rendered,
                webpageTypes(), 200000);
    }

    private WebpageTypeCatalogPort webpageTypes() {
        return new WebpageTypeCatalog(new WebpageTypeProperties());
    }

    private final Address address = new Address("ADDR-001", "News",
            "https://example.eu/news", AddressType.URL_NEWS);

    @Test
    void aStaticContentPageClassifiesStaticWithTheHttpConnector() {
        var outcome = connector(serving(STATIC_HTML, "text/html"),
                noRenderedConnector()).preflight(address);
        assertEquals("static", outcome.webpageType());
        assertEquals("http", outcome.estimatedConnector());
        assertTrue(outcome.preflightJson().contains("\"httpStatus\":200"));
        assertTrue(outcome.preflightJson().contains("\"contentType\":\"text/html\""));
        assertTrue(outcome.preflightJson().contains("\"shellIndicator\":null"));
        assertTrue(outcome.preflightJson().contains("\"renderedProbe\":false"));
        assertTrue(outcome.evidence().contains("static fetch"));
    }

    @Test
    void anApplicationShellMarksTheShellIndicatorAndClassification() {
        var outcome = connector(serving(ANGULAR_SHELL, "text/html"),
                noRenderedConnector()).preflight(address);
        assertEquals("spa-angular", outcome.webpageType());
        assertEquals("rendered", outcome.estimatedConnector());
        assertNotNull(outcome.preflightJson());
        assertTrue(outcome.preflightJson().contains("app-root"),
                outcome.preflightJson());
        assertTrue(outcome.evidence().contains("shell indicator"));
    }

    @Test
    void aShellWithAPlaywrightConnectorRunsTheRenderedProbe() {
        var rendered = new es.jllopez.neis.infrastructure.adapter.scrape.PlaywrightRenderConnector(
                1048576, 5) {
            @Override
            public ScrapeResult scrape(Address a) {
                return new ScrapeResult(RENDERED_HTML, "text/html",
                        RENDERED_HTML.length());
            }
        };
        ObjectProvider<es.jllopez.neis.infrastructure.adapter.scrape.PlaywrightRenderConnector>
                provider = new ObjectProvider<>() {
            @Override
            public es.jllopez.neis.infrastructure.adapter.scrape.PlaywrightRenderConnector getObject() {
                return rendered;
            }

            @Override
            public es.jllopez.neis.infrastructure.adapter.scrape.PlaywrightRenderConnector getIfAvailable() {
                return rendered;
            }
        };
        var outcome = connector(serving(ANGULAR_SHELL, "text/html"),
                provider).preflight(address);
        assertTrue(outcome.preflightJson().contains("\"renderedProbe\":true"));
        assertEquals("spa-angular", outcome.webpageType());
    }

    @Test
    void aFeedDocumentEstimatesTheFeedConnector() {
        String feed = """
                <?xml version="1.0" encoding="UTF-8"?>
                <rss version="2.0"><channel><title>Feed</title>
                <item><title>One</title></item></channel></rss>
                """;
        var outcome = connector(serving(feed, "application/rss+xml"),
                noRenderedConnector()).preflight(address);
        assertEquals("feed", outcome.webpageType());
        assertEquals("feed", outcome.estimatedConnector());
    }

    @Test
    void anUnknownTypeLeavesTheConnectorEstimateOpen() {
        var outcome = connector(serving("", "application/octet-stream"),
                noRenderedConnector()).preflight(address);
        assertNull(outcome.webpageType());
        assertNull(outcome.estimatedConnector());
        assertTrue(outcome.evidence().contains("unclassified"));
    }

    @Test
    void theEstimateFollowsTheTaxonomyStrategy() {
        // spa-react is a built-in of the taxonomy mapped to rendered.
        var outcome = connector(serving(
                "<html><body><div id=\"root\"></div>"
                        + "<script>__NEXT_DATA__={}</script></body></html>",
                "text/html"), noRenderedConnector()).preflight(address);
        assertEquals("spa-react", outcome.webpageType());
        assertEquals(WebpageType.STRATEGY_RENDERED, webpageTypes()
                .byId(outcome.webpageType()).map(WebpageType::strategy).orElse(""));
        assertEquals("rendered", outcome.estimatedConnector());
    }
}
