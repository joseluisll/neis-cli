package es.jllopez.neis.domain.model;

import java.util.List;

/**
 * The outcome of the obstruction clearing (US-06.7, issue #75): the
 * cleaned HTML5 and the record of what was removed, so the capture
 * metadata (US-11.4) can state what the stored HTML5 no longer
 * contains.
 *
 * @param html           the cleared HTML5 (still valid markup)
 * @param sizeBytes      byte size of the cleared HTML5 (UTF-8)
 * @param removedCount   number of elements removed (all rules combined)
 * @param rulesApplied   the rule ids that removed at least one element,
 *                       in application order (generic rule ids and the
 *                       configured selectors as given)
 */
public record ClearedContent(String html, int sizeBytes, int removedCount,
                             List<String> rulesApplied) {

    public ClearedContent {
        if (html == null) {
            throw new IllegalArgumentException("Cleared content needs the cleaned HTML5");
        }
        rulesApplied = rulesApplied == null ? List.of() : List.copyOf(rulesApplied);
    }
}
