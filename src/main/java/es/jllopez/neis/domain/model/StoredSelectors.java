package es.jllopez.neis.domain.model;

import java.util.List;

/**
 * Validated, working selectors persisted together with their source
 * address, so future chain runs use them directly without invoking the AI
 * (owner decision 2026-11-11, MVP issue #90; requirement US-12.1's
 * selector configuration per task; the filter-zone selector added for
 * US-17.1: ANALYZE identifies the filter zone the same way it identifies
 * the news/events item selectors; the filter catalogue added for the
 * owner's offline-filter decision 2026-11-12: every extracted filter
 * field with ALL its possible values, so the FILTER step works without
 * the AI).
 *
 * @param addressId      the address these selectors work on
 * @param kind           "news" or "event"
 * @param selector       CSS selector of the repeating list item,
 *                       validated against the simplified HTML
 * @param filterSelector CSS selector of the filter zone (the form or
 *                       container holding the filter controls),
 *                       validated the same way; null when the address
 *                       offers no filtering or it was not identified
 * @param filters        the filter catalogue: every extracted field with
 *                       all its possible values, captured from within the
 *                       identified zone; empty when the address offers no
 *                       filtering or nothing was extracted yet
 * @param discoveredBy   "ai" (AI-identified and validated) — fallback
 *                       selectors are not persisted
 * @param discoveredAt   ISO-8601 date of discovery
 * @param notes          AI reasoning / provenance
 */
public record StoredSelectors(
        String addressId,
        String kind,
        String selector,
        String filterSelector,
        List<FilterDefinition> filters,
        String discoveredBy,
        String discoveredAt,
        String notes) {

    public StoredSelectors {
        filters = filters == null ? List.of() : List.copyOf(filters);
    }

    /** Selector set without a filter-zone selector (pre-US-17.1 shape). */
    public StoredSelectors(String addressId, String kind, String selector,
                           String discoveredBy, String discoveredAt, String notes) {
        this(addressId, kind, selector, null, List.of(), discoveredBy, discoveredAt, notes);
    }

    /** Selector set with a filter-zone selector but no catalogue yet. */
    public StoredSelectors(String addressId, String kind, String selector,
                           String filterSelector,
                           String discoveredBy, String discoveredAt, String notes) {
        this(addressId, kind, selector, filterSelector, List.of(),
                discoveredBy, discoveredAt, notes);
    }
}
