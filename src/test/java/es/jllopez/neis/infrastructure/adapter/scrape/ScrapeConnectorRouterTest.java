package es.jllopez.neis.infrastructure.adapter.scrape;

import es.jllopez.neis.domain.model.Address;
import es.jllopez.neis.domain.model.ScrapeResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The SCRAPE routing connector: configuration picks static fixtures
 * (default) or the live HTTP fetcher; an unknown value fails instead
 * of silently falling back to fixtures.
 */
class ScrapeConnectorRouterTest {

    private static final Address ADDRESS = new Address("ADDR-001", "Seed", null,
            "https://example.invalid/list", es.jllopez.neis.domain.model.AddressType.URL_NEWS);

    @Test
    void staticModeServesTheFixtures() {
        ScrapeConnectorRouter router = new ScrapeConnectorRouter(
                new StaticScrapeConnector(), emptyProvider(), "static");

        ScrapeResult result = router.scrape(ADDRESS);

        assertEquals("text/html; charset=UTF-8", result.contentType());
        assertTrue(result.html().contains("<"), "fixture HTML body");
    }

    @Test
    void liveModeDelegatesToTheHttpConnector() {
        HttpScrapeConnector stub = new HttpScrapeConnector(5, 1024 * 1024) {
            @Override
            public ScrapeResult scrape(Address address) {
                return new ScrapeResult("<html><body>live</body></html>",
                        "text/html", 33);
            }
        };
        ScrapeConnectorRouter router = new ScrapeConnectorRouter(
                new StaticScrapeConnector(), singleProvider(stub), "live");

        ScrapeResult result = router.scrape(ADDRESS);

        assertEquals("<html><body>live</body></html>", result.html());
    }

    @Test
    void defaultModeIsStatic() {
        ScrapeConnectorRouter router = new ScrapeConnectorRouter(
                new StaticScrapeConnector(), emptyProvider(), null);

        ScrapeResult result = router.scrape(ADDRESS);

        assertEquals("text/html; charset=UTF-8", result.contentType());
    }

    @Test
    void unknownModeFailsTheBoot() {
        assertThrows(IllegalArgumentException.class, () -> new ScrapeConnectorRouter(
                new StaticScrapeConnector(), emptyProvider(), "http"));
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
}
