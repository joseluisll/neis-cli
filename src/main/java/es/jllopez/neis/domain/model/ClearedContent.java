package es.jllopez.neis.domain.model;

import java.util.List;

/**
 * The outcome of the obstruction clearing (US-06.7, issue #75): the
 * cleaned HTML5 and the record of what was removed, so the capture
 * metadata (US-11.4) can state what the stored HTML5 no longer
 * contains.
 *
 * <p>Since US-06.13 (issue #137) the record also carries the
 * CLASSIFIED dialog encounters: consent dialogs (cookie/data-use),
 * other dismissible modals (newsletter/registration walls) and plain
 * obstructing elements (ads, configured selectors) — each with the
 * action taken (the configured consent policy for consent dialogs;
 * plain removal for the rest), so the capture provenance states not
 * only HOW MUCH was removed but WHAT KIND and UNDER WHICH policy.</p>
 *
 * @param html           the cleared HTML5 (still valid markup)
 * @param sizeBytes      byte size of the cleared HTML5 (UTF-8)
 * @param removedCount   number of elements removed (all rules combined)
 * @param rulesApplied   the rule ids that removed at least one element,
 *                       in application order (generic rule ids and the
 *                       configured selectors as given)
 * @param dialogs        the classified dialog encounters (consent /
 *                       modal counts with the action taken); empty when
 *                       the page carried no classified overlays
 */
public record ClearedContent(String html, int sizeBytes, int removedCount,
                             List<String> rulesApplied,
                             List<DialogEncounter> dialogs) {

    /** One classified dialog encounter: the overlay type (consent /
     * modal), how many elements of that type were dismissed, and the
     * action taken (e.g. "dismissed; consent policy REJECT recorded"). */
    public record DialogEncounter(String type, int count, String action) {}

    public ClearedContent {
        if (html == null) {
            throw new IllegalArgumentException("Cleared content needs the cleaned HTML5");
        }
        rulesApplied = rulesApplied == null ? List.of() : List.copyOf(rulesApplied);
        dialogs = dialogs == null ? List.of() : List.copyOf(dialogs);
    }

    /** The pre-US-06.13 shape (no classified dialogs). */
    public ClearedContent(String html, int sizeBytes, int removedCount,
                          List<String> rulesApplied) {
        this(html, sizeBytes, removedCount, rulesApplied, List.of());
    }
}
