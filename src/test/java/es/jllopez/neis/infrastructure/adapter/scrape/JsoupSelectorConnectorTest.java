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
}
