package es.jllopez.neis.domain.model;

import java.util.List;
import java.util.Map;

/**
 * The pruned element tree and its catalog (US-06.9, issue #88):
 * Skyvern's scraper output re-implemented in Java (no code copied) —
 * the numbered tree of relevant elements, the change digest of the
 * tree per capture (US-11.4: unchanged pages are detected between
 * runs) and the placeholder map of hashed long URLs.
 *
 * @param elements    the pruned tree in document order (dropped
 *                    non-interactable elements are absent; dropped
 *                    interactable ones carry only their tag)
 * @param digest      SHA-256 (hex) of the canonical tree rendering —
 *                    the same page content always yields the same
 *                    digest, so unchanged pages are detected between
 *                    runs without any lookup
 * @param placeholders long values (> 2048 chars) hashed into
 *                    {@code hash:<12 hex>} placeholders, mapped back
 *                    to the full value so nothing is lost
 */
public record ElementCatalog(List<PrunedElement> elements, String digest,
                             Map<String, String> placeholders) {

    public ElementCatalog {
        elements = elements == null ? List.of() : List.copyOf(elements);
        // Deterministic rendering (US-06.9 review B2 finding): the
        // placeholder map's iteration order flows into the catalog
        // JSON and the digest — a hash-ordered copy would make the
        // rendering JVM-dependent; insertion order is preserved.
        placeholders = placeholders == null || placeholders.isEmpty()
                ? Map.of() : java.util.Collections.unmodifiableMap(
                        new java.util.LinkedHashMap<>(placeholders));
        if (digest == null || digest.isBlank()) {
            throw new IllegalArgumentException("An element catalog needs a digest");
        }
    }
}
