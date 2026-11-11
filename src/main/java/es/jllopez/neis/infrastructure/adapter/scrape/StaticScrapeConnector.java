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
 * <p>Fixture provenance: all fixtures are REAL pages of the issue #62
 * seed addresses (ADDR-005/006: HADEA lists, US-17 work), captured
 * 2026-11-11 with a real browser — Node + Playwright (Chromium) via
 * {@code scripts/fetch-rendered-html.js} (wait for network idle, then
 * serialize the DOM). ADDR-001/002 are Confluence pages; ADDR-003/004
 * are Angular SPAs whose lists only exist client-side; ADDR-005/006 are
 * Drupal (OpenEuropa) list pages. All are Playwright-RENDERED DOMs (a
 * plain HTTP fetch would not return the list). Paginated addresses
 * store one fixture per page, keyed {@code <id>#p<N>} for page N
 * (page 0 = base fixture) — the chain's pagination-iteration block
 * addresses pages through the port's page index. Regenerate with
 * {@code node scripts/fetch-rendered-html.js <dir>}.</p>
 */
@Component
public class StaticScrapeConnector implements ContentScrapePort {

    private static final Map<String, String> FIXTURES = Map.ofEntries(
            Map.entry("ADDR-001", "/mvp/samples/addr-001-news.html"),
            Map.entry("ADDR-002", "/mvp/samples/addr-002-events.html"),
            Map.entry("ADDR-003", "/mvp/samples/addr-003-news-spa-rendered.html"),
            Map.entry("ADDR-004", "/mvp/samples/addr-004-events-spa-rendered.html"),
            Map.entry("ADDR-005", "/mvp/samples/addr-005-events-rendered.html"),
            Map.entry("ADDR-005#p1", "/mvp/samples/addr-005-events-page2-rendered.html"),
            Map.entry("ADDR-006", "/mvp/samples/addr-006-news-rendered.html"),
            Map.entry("ADDR-007", "/mvp/samples/addr-007-news-rendered.html"),
            Map.entry("ADDR-007#p1", "/mvp/samples/addr-007-news-page2-rendered.html"),
            Map.entry("ADDR-007#p2", "/mvp/samples/addr-007-news-page3-rendered.html"),
            Map.entry("ADDR-007#p3", "/mvp/samples/addr-007-news-page4-rendered.html"),
            Map.entry("ADDR-007#p4", "/mvp/samples/addr-007-news-page5-rendered.html"),
            Map.entry("ADDR-008", "/mvp/samples/addr-008-events-rendered.html"),
            Map.entry("ADDR-008#p1", "/mvp/samples/addr-008-events-page2-rendered.html"),
            Map.entry("ADDR-008#p2", "/mvp/samples/addr-008-events-page3-rendered.html"),
            Map.entry("ADDR-008#p3", "/mvp/samples/addr-008-events-page4-rendered.html"),
            Map.entry("ADDR-008#p4", "/mvp/samples/addr-008-events-page5-rendered.html"));

    @Override
    public ScrapeResult scrape(Address address) {
        return scrape(address, 0);
    }

    @Override
    public ScrapeResult scrape(Address address, int pageIndex) {
        String resource = FIXTURES.get(
                pageIndex == 0 ? address.id() : address.id() + "#p" + pageIndex);
        if (resource == null) {
            throw new IllegalStateException(
                    "No static fixture for address " + address.id()
                            + (pageIndex == 0 ? "" : " page " + pageIndex)
                            + " (MVP SCRAPE serves fixtures of issue #62 / US-17 only)");
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
