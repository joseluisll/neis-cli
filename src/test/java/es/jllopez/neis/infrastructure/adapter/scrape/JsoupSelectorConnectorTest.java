package es.jllopez.neis.infrastructure.adapter.scrape;

import es.jllopez.neis.domain.model.NewsEventItem;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The selector-based extraction engine (US-12.2, issue #47):
 * deterministic iteration over the CSS-selector matches, per-item
 * metadata (title, description, date, detail URL) and — pinned here —
 * relative detail links resolved to absolute URLs against the address's
 * base URI.
 */
class JsoupSelectorConnectorTest {

    private final JsoupSelectorConnector connector = new JsoupSelectorConnector();

    private static final String LISTING = "<html><head><title>news</title></head><body>"
            + "<ul class='news-list'>"
            + "<li><h2><a href='/news/one'>First item</a></h2>"
            + "<p>Summary of the first item.</p>"
            + "<time datetime='2026-11-14'>14 November 2026</time></li>"
            + "<li><h2><a href='/news/two'>Second item</a></h2>"
            + "<p>Summary of the second item.</p></li>"
            + "</ul></body></html>";

    @Test
    void extractsItemsDeterministically() {
        List<NewsEventItem> first = connector.extract(
                LISTING, "ul.news-list li", "news", "ADDR-T", 50, null);
        List<NewsEventItem> second = connector.extract(
                LISTING, "ul.news-list li", "news", "ADDR-T", 50, null);

        assertEquals(first, second, "same input, same items — no AI involved");
        assertEquals(2, first.size());
        NewsEventItem item = first.get(0);
        assertEquals("First item", item.title());
        assertEquals("Summary of the first item.", item.description());
        assertEquals("2026-11-14", item.date());
        assertEquals("news", item.kind());
    }

    @Test
    void relativeDetailLinksResolveAgainstTheBaseUrl() {
        List<NewsEventItem> withBase = connector.extract(
                LISTING, "ul.news-list li", "news", "ADDR-T", 50,
                "https://example.eu/news/");
        assertEquals("https://example.eu/news/one", withBase.get(0).url());
        assertEquals("https://example.eu/news/two", withBase.get(1).url());

        // Without a base URI the relative href stays as-is (documented
        // behavior, not an error).
        List<NewsEventItem> withoutBase = connector.extract(
                LISTING, "ul.news-list li", "news", "ADDR-T", 50, null);
        assertEquals("/news/one", withoutBase.get(0).url());
    }

    @Test
    void maxItemsCapsTheIteration() {
        List<NewsEventItem> items = connector.extract(
                LISTING, "ul.news-list li", "news", "ADDR-T", 1, null);

        assertEquals(1, items.size());
        assertEquals("First item", items.get(0).title());
    }

    @Test
    void invalidSelectorYieldsNoItems() {
        assertTrue(connector.extract(
                LISTING, "###not-a-selector", "news", "ADDR-T", 50, null).isEmpty());
        assertTrue(connector.extract(
                LISTING, null, "news", "ADDR-T", 50, null).isEmpty());
    }

    @Test
    void documentBaseTagOverridesThePassedBaseUri() {
        // jsoup resolves a document-level <base href> against the passed
        // baseUri and lets it win — standard HTML semantics; the address
        // URL is silently not-the-base for such pages (US-12.2 review
        // note, 2026-11-14). Pinned so the behavior stays explicit.
        String html = "<html><head><base href='https://cdn.example.eu/items/'></head>"
                + "<body><ul class='news-list'>"
                + "<li><a href='one.html'>Item one</a></li></ul></body></html>";

        NewsEventItem item = connector.extract(
                html, "ul.news-list li", "news", "ADDR-T", 50,
                "https://example.eu/news/").get(0);

        assertEquals("https://cdn.example.eu/items/one.html", item.url());
    }

    @Test
    void nextPageUrlFollowsRelNext() {
        // US-12.4: the rel=next link relation is the primary next-page
        // control; resolved to an absolute URL against the base.
        String html = "<html><body><ul class='news-list'><li>item</li></ul>"
                + "<nav class='pagination'><a rel='next' href='/news?page=2'>Next</a></nav>"
                + "</body></html>";

        assertEquals("https://example.eu/news?page=2",
                connector.nextPageUrl(html, "https://example.eu/news"));
    }

    @Test
    void nextPageUrlFollowsANextLabelledPagerLink() {
        String html = "<html><body><ul class='news-list'><li>item</li></ul>"
                + "<div class='paginator'><a href='/news/page/2'>siguiente</a></div>"
                + "</body></html>";

        assertEquals("https://example.eu/news/page/2",
                connector.nextPageUrl(html, "https://example.eu/news"));
    }

    @Test
    void nextPageUrlIsNullWithoutAControl() {
        String html = "<html><body><ul class='news-list'><li>item</li></ul>"
                + "<div class='paginator'><a href='/news/page/1'>1</a></div>"
                + "</body></html>";

        assertEquals(null, connector.nextPageUrl(html, "https://example.eu/news"));
        assertEquals(null, connector.nextPageUrl(null, "https://example.eu/news"));
    }

    @Test
    void nextPageUrlIgnoresNonWebSchemes() {
        // javascript:/mailto: pager anchors are common in the wild and
        // would crash the fetch — they are not next-page controls
        // (US-12.4 review finding, 2026-11-14).
        String html = "<html><body><ul class='news-list'><li>item</li></ul>"
                + "<nav class='pagination'>"
                + "<a rel='next' href='javascript:void(0)'>Next</a>"
                + "<a href='mailto:webmaster@example.eu'>Next</a></nav>"
                + "</body></html>";

        assertEquals(null, connector.nextPageUrl(html, "https://example.eu/news"));
    }
}
