package es.jllopez.neis.domain.port;

import es.jllopez.neis.domain.model.NewsEventItem;

import java.util.List;

/**
 * Port for selector-based extraction of news/events items from HTML5
 * content (requirement US-12.2, issue #47; selectors come from the
 * ANALYZE step, US-13.3/US-13.4).
 */
public interface SelectorExtractionPort {

    /**
     * Extracts list items matching the CSS selector.
     *
     * @param html       HTML5 content (raw or simplified)
     * @param cssSelector CSS selector identifying the repeating items
     * @param kind       "news" or "event"
     * @param addressId  owning address id
     * @param maxItems   extraction limit (US-12.5 spirit)
     */
    List<NewsEventItem> extract(String html, String cssSelector, String kind,
                                String addressId, int maxItems);

    /**
     * Number of elements the CSS selector matches in the given HTML.
     * Used to validate identified selectors by probe (e.g. the filter
     * zone must match exactly one element) — validation by extraction,
     * no additional stack dependency.
     */
    int countMatches(String html, String cssSelector);

    /**
     * Outer HTML of the single element the CSS selector matches, or null
     * when it does not match exactly one element. Lets a block scope its
     * work (e.g. the FILTER block extracts definitions from within the
     * identified filter zone) without its own HTML-stack dependency.
     */
    String zoneHtml(String html, String cssSelector);
}
