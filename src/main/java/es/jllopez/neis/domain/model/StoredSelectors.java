package es.jllopez.neis.domain.model;

/**
 * A validated, working selector persisted together with its source
 * address, so future chain runs use it directly without invoking the AI
 * (owner decision 2026-11-11, MVP issue #90; requirement US-12.1's
 * selector configuration per task).
 *
 * @param addressId    the address this selector works on
 * @param kind         "news" or "event"
 * @param selector      CSS selector validated against the simplified HTML
 * @param discoveredBy "ai" (AI-identified and validated) — fallback
 *                     selectors are not persisted
 * @param discoveredAt ISO-8601 date of discovery
 * @param notes        AI reasoning / provenance
 */
public record StoredSelectors(
        String addressId,
        String kind,
        String selector,
        String discoveredBy,
        String discoveredAt,
        String notes) {
}
