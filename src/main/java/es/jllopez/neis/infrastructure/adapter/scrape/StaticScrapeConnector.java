package es.jllopez.neis.infrastructure.adapter.scrape;

import es.jllopez.neis.domain.model.Address;
import es.jllopez.neis.domain.model.ScrapeResult;
import es.jllopez.neis.domain.port.ContentScrapePort;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Connector (driven adapter) implementing the SCRAPE port with bundled
 * static HTML5 fixtures — the MVP half implementation of US-06.1: no
 * network access at chain runtime, the fixture stands in for what the
 * real fetch would return (issue #90).
 *
 * <p>Fixture provenance: all four fixtures are REAL pages of the issue
 * #62 seed addresses, captured 2026-11-11 with a real browser —
 * Node + Playwright (Chromium) via {@code scripts/fetch-rendered-html.js}
 * (wait for network idle, then serialize the DOM). ADDR-001/002 are
 * Confluence pages; ADDR-003/004 are Angular SPAs whose lists only exist
 * client-side, so the fixture is the Playwright-RENDERED DOM (a plain
 * HTTP fetch returns an empty app shell — US-06.1 rendering connector).
 * Regenerate with {@code node scripts/fetch-rendered-html.js <dir>}.</p>
 */
@Component
public class StaticScrapeConnector implements ContentScrapePort {

    private static final Map<String, String> FIXTURES = Map.of(
            "ADDR-001", "/mvp/samples/addr-001-news.html",
            "ADDR-002", "/mvp/samples/addr-002-events.html",
            "ADDR-003", "/mvp/samples/addr-003-news-spa-rendered.html",
            "ADDR-004", "/mvp/samples/addr-004-events-spa-rendered.html");

    @Override
    public ScrapeResult scrape(Address address) {
        String resource = FIXTURES.get(address.id());
        if (resource == null) {
            throw new IllegalStateException(
                    "No static fixture for address " + address.id()
                            + " (MVP SCRAPE serves fixtures of issue #62 only)");
        }
        try (InputStream in = getClass().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("Missing classpath fixture " + resource);
            }
            String html = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return new ScrapeResult(html, "text/html; charset=UTF-8",
                    html.getBytes(StandardCharsets.UTF_8).length);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read fixture " + resource, e);
        }
    }
}
