package es.jllopez.neis.infrastructure.adapter.scrape;

import es.jllopez.neis.domain.model.Address;
import es.jllopez.neis.domain.model.ScrapeResult;
import es.jllopez.neis.domain.port.ContentScrapePort;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
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
            Map.entry("ADDR-008#p4", "/mvp/samples/addr-008-events-page5-rendered.html"),
            Map.entry("ADDR-009", "/mvp/samples/addr-009-news-rendered.html"),
            Map.entry("ADDR-009#p1", "/mvp/samples/addr-009-news-page2-rendered.html"),
            Map.entry("ADDR-009#p2", "/mvp/samples/addr-009-news-page3-rendered.html"),
            Map.entry("ADDR-009#p3", "/mvp/samples/addr-009-news-page4-rendered.html"),
            Map.entry("ADDR-009#p4", "/mvp/samples/addr-009-news-page5-rendered.html"),
            // Captured FILTERED responses (US-06.6 slice): one real
            // filtered page per FILTERING-flagged address, captured with
            // the same real browser as the base fixtures after applying
            // one filter criterion of the site's own filter form
            // (scripts/fetch-filtered-html.js). The capture stands in
            // for what the site returns AFTER the filter request; which
            // criterion each embodies is recorded in mvp/samples/README.md.
            Map.entry("ADDR-003#filter", "/mvp/samples/addr-003-news-filtered-rendered.html"),
            Map.entry("ADDR-004#filter", "/mvp/samples/addr-004-events-filtered-rendered.html"),
            Map.entry("ADDR-005#filter", "/mvp/samples/addr-005-events-filtered-rendered.html"),
            Map.entry("ADDR-006#filter", "/mvp/samples/addr-006-news-filtered-rendered.html"),
            Map.entry("ADDR-007#filter", "/mvp/samples/addr-007-news-filtered-rendered.html"),
            Map.entry("ADDR-008#filter", "/mvp/samples/addr-008-events-filtered-rendered.html"));

    /**
     * The criterion each captured filtered response embodies (one per
     * FILTERING-flagged address; see the registry above and
     * mvp/samples/README.md). The apply prompt constrains the AI's
     * choice to these requests, so the reported criterion always
     * corresponds to the captured response the connector serves.
     */
    private static final Map<String, String> FILTER_CRITERIA = Map.of(
            "ADDR-003", "Programme = Horizon Europe (HORIZON) (request: programme=HORIZON)",
            "ADDR-004", "Status = Past (request: status=PAST)",
            "ADDR-005", "Event status = Past (request: facets[status]=past)",
            "ADDR-006", "Programme = EU4Health (request: programme=EU4Health)",
            "ADDR-007", "Responsible body = CHMP (request: responsible_body=CHMP)",
            "ADDR-008", "Category = Human (request: facets[category]=human)");

    @Override
    public ScrapeResult scrape(Address address) {
        return scrape(address, 0);
    }

    @Override
    public List<String> supportedFilterRequests(Address address) {
        String criterion = FILTER_CRITERIA.get(address.id());
        return criterion == null ? List.of() : List.of(criterion);
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
        return readFixture(address, resource);
    }

    @Override
    public ScrapeResult scrapeFiltered(Address address, String filterRequest) {
        String resource = FIXTURES.get(address.id() + "#filter");
        if (resource == null || getClass().getResource(resource) == null) {
            throw new UnsupportedOperationException(
                    "No captured filtered response for address " + address.id()
                            + " (MVP SCRAPE cannot apply the filter request '" + filterRequest
                            + "')");
        }
        return readFixture(address, resource);
    }

    private ScrapeResult readFixture(Address address, String resource) {
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
