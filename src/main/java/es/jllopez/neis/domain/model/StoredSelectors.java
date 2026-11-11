package es.jllopez.neis.domain.model;

/**
 * Validated, working selectors persisted together with their source
 * address, so future chain runs use them directly without invoking the AI
 * (owner decision 2026-11-11, MVP issue #90; requirement US-12.1's
 * selector configuration per task; the filter-zone selector added for
 * US-17.1: ANALYZE identifies the filter zone the same way it identifies
 * the news/events item selectors).
 *
 * @param addressId         the address these selectors work on
 * @param kind              "news" or "event"
 * @param selector          CSS selector of the repeating list item,
 *                           validated against the simplified HTML
 * @param filterSelector    CSS selector of the filter zone (the form or
 *                           container holding the filter controls),
 *                           validated the same way; null when the address
 *                           offers no filtering or it was not identified
 * @param discoveredBy      "ai" (AI-identified and validated) — fallback
 *                          selectors are not persisted
 * @param discoveredAt      ISO-8601 date of discovery
 * @param notes             AI reasoning / provenance
 */
public record StoredSelectors(
        String addressId,
        String kind,
        String selector,
        String filterSelector,
        String discoveredBy,
        String discoveredAt,
        String notes) {

    /** Selector set without a filter-zone selector (pre-US-17.1 shape). */
    public StoredSelectors(String addressId, String kind, String selector,
                           String discoveredBy, String discoveredAt, String notes) {
        this(addressId, kind, selector, null, discoveredBy, discoveredAt, notes);
    }
}
