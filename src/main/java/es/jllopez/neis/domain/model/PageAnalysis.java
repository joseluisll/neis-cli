package es.jllopez.neis.domain.model;

/**
 * Result of the ANALYZE step: AI classification of the page and the
 * AI-identified selectors for news and events lists
 * (requirements US-13.2, US-13.3, US-13.4 — issues #51-#53).
 *
 * @param classification "news", "event" or "other"
 * @param newsSelector   CSS selector for news list items (may be null)
 * @param eventsSelector CSS selector for event list items (may be null)
 * @param aiAssisted     true when a live AI produced the analysis; false when
 *                       the fallback heuristics were used
 * @param notes          free-form detail (AI reasoning, fallback reason, ...)
 */
public record PageAnalysis(
        String classification,
        String newsSelector,
        String eventsSelector,
        boolean aiAssisted,
        String notes) {

    /** Fallback analysis derived from the address type when AI is absent. */
    public static PageAnalysis fallback(String kind, String reason) {
        String classification = "news".equals(kind) ? "news" : "event";
        return new PageAnalysis(classification, null, null, false, reason);
    }
}
