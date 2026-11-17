package es.jllopez.neis.domain.model;

import java.util.Map;

/**
 * One element of the pruned element tree (US-06.9, issue #88):
 * Skyvern's element-tree concept re-implemented in Java (no code
 * copied) — a stable incremental identifier plus the catalog entry
 * the selectors, the AI analysis and users reference elements by.
 *
 * @param id           the stable incremental id of this analysis
 *                     ({@code e1}, {@code e2}, ... in document order)
 * @param tag          the element's tag name (lowercase)
 * @param cssPath      a CSS selector that locates this element (the
 *                     id-to-CSS map: deterministic jsoup-supported
 *                     path of tags, ids and nth-of-type positions)
 * @param role         the {@code role} attribute, or the implicit
 *                     landmark role of the tag when it has one
 * @param text         the element's own normalized text (truncated)
 * @param attributes   the whitelisted attributes of interest, values
 *                     trimmed; long URLs hashed into the catalog's
 *                     placeholder map
 * @param interactable true when the element is actionable (link,
 *                     button, form control) — enabled/disabled state
 *                     is part of {@code attributes}
 * @param dropped      true when the element was marked dropped by the
 *                     visibility/relevance filtering (Skyvern's
 *                     isDropped): a dropped NON-interactable element
 *                     is pruned entirely; a dropped INTERACTABLE one
 *                     keeps its tag but loses its attributes
 */
public record PrunedElement(String id, String tag, String cssPath, String role,
                            String text, Map<String, String> attributes,
                            boolean interactable, boolean dropped) {

    /** The attributes of interest (Skyvern's reserved attributes), in
     * FIXED order: the whitelist is a List, not a Set — the attribute
     * order flows into the canonical digest line and the catalog JSON,
     * and a Set.of iteration order is salted per JVM (JDK 9+), which
     * made the digest of a byte-identical page differ between CLI runs
     * (review B2 finding: determinism is the whole point of the
     * change-detection digest). */
    public static final java.util.List<String> WHITELISTED_ATTRIBUTES =
            java.util.List.of("id", "class", "role", "href", "name", "type",
                    "value", "placeholder", "aria-label", "aria-disabled",
                    "disabled", "title", "alt", "for", "action", "method",
                    "rel", "datetime", "checked", "selected", "required",
                    "readonly", "multiple", "aria-expanded", "aria-current");

    /** Value length cap: longer values are truncated (trim_element). */
    public static final int MAX_VALUE_LENGTH = 200;

    /** URL length beyond which the value is hashed into a placeholder. */
    public static final int MAX_URL_LENGTH = 2048;

    public PrunedElement {
        // Deterministic ordering (US-06.9 review B2 finding): the
        // attrs map's iteration order flows into the canonical digest
        // line and the catalog JSON — Map.copyOf returns an
        // unspecified (typically hash-ordered) map, so preserve the
        // whitelist's insertion order instead.
        attributes = attributes == null || attributes.isEmpty() ? Map.of()
                : java.util.Collections.unmodifiableMap(
                        new java.util.LinkedHashMap<>(attributes));
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("A pruned element needs an id");
        }
    }
}
