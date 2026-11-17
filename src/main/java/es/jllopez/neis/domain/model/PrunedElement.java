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

    /** The attributes of interest (Skyvern's reserved attributes). */
    public static final java.util.Set<String> WHITELISTED_ATTRIBUTES =
            java.util.Set.of("id", "class", "role", "href", "name", "type",
                    "value", "placeholder", "aria-label", "aria-disabled",
                    "disabled", "title", "alt", "for", "action", "method",
                    "rel", "datetime", "checked", "selected", "required",
                    "readonly", "multiple", "aria-expanded", "aria-current");

    /** Value length cap: longer values are truncated (trim_element). */
    public static final int MAX_VALUE_LENGTH = 200;

    /** URL length beyond which the value is hashed into a placeholder. */
    public static final int MAX_URL_LENGTH = 2048;

    public PrunedElement {
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("A pruned element needs an id");
        }
    }
}
