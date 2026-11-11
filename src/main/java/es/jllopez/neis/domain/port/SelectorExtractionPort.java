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
}
