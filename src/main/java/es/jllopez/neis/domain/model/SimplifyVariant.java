package es.jllopez.neis.domain.model;

import java.util.List;

/**
 * The tiered SIMPLIFY variants (US-07.6, issue #89): Skyvern's
 * tree-variant concept (full / economy / lean) re-implemented in Java
 * — one simplified HTML5 output, three reduction tiers on demand.
 * Every tier respects the data-preservation rule (US-07.4): removal is
 * maximal only where no data is lost.
 *
 * <ul>
 *   <li>{@link #FULL} — the complete simplified tree: every kept
 *   element with its whitelisted attributes and text.</li>
 *   <li>{@link #ECONOMY} — a percentage/size target: the tree is
 *   reduced until it fits the configured fraction of the full variant
 *   (longest texts truncated first, then textless presentational
 *   containers dropped) — the AI-analysis budget tier.</li>
 *   <li>{@link #LEAN} — the compact tier: long hrefs/srcs compressed
 *   into hashed placeholders with a side table, query strings
 *   stripped, non-navigable links collapsed to their text — the
 *   smallest faithful rendering.</li>
 * </ul>
 */
public record SimplifyVariant(String id) {

    public static final String FULL = "full";
    public static final String ECONOMY = "economy";
    public static final String LEAN = "lean";

    /** The known variants, fixed order (deterministic enumeration). */
    public static final List<String> ALL = List.of(FULL, ECONOMY, LEAN);

    public SimplifyVariant {
        // Null/blank means "the default tier" (full): the SIMPLIFY
        // overload without an explicit variant must keep working —
        // only a WRONG variant name is a loud error.
        if (id == null || id.isBlank()) {
            id = FULL;
        }
        if (!ALL.contains(id)) {
            throw new IllegalArgumentException(
                    "Unknown SIMPLIFY variant: " + id + " (known: " + ALL + ")");
        }
    }
}
