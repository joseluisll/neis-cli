package es.jllopez.neis.domain.model;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One manual item correction (US-14.10, issue #124): the operator's
 * fix of a mis-extracted item's metadata — title, description, date
 * and/or kind. Null fields keep the stored value. The correction is
 * TRACEABLE, never a silent overwrite: the store records what changed
 * (old value -> new value, per field), when and by whom, as a JSON
 * entry on the corrected rows (the {@code corrections} column, V22).
 *
 * <p>Canonical identity (US-14.5) survives corrections: the canonical
 * id is the item's identity anchor and is NEVER recomputed by a
 * correction, so the sighting history stays linked. The decided,
 * documented case: on a url-less item (whose canonical key is
 * title+date) a correction of title or date changes what the key
 * WOULD compute — the stored canonical id still does not move; a
 * later re-extraction of the same story then lands under the freshly
 * computed id as a new logical record (the operator's edit is a
 * statement about the stored record, not about future extractions).
 * Provenance (address, execution, content record) is never touched.</p>
 *
 * <p>The apply helpers live in the domain so the real connector and
 * the in-memory test double cannot drift (the shared-logic rule).</p>
 */
public record NewsItemCorrection(
        String title,
        String description,
        String date,
        String kind,
        String note) {

    /** True when the correction changes nothing (nothing to record). */
    public boolean isEmpty() {
        return (title == null || title.isBlank())
                && (description == null || description.isBlank())
                && (date == null || date.isBlank())
                && (kind == null || kind.isBlank());
    }

    /**
     * The corrected snapshot of {@code item}: every provided field
     * replaced, the date re-normalized through the ONE shared
     * normalizer (US-14.9), the mandatory-metadata flags recomputed
     * (US-14.4). The canonical id, url and provenance components are
     * carried over UNCHANGED.
     */
    public NewsEventItem applyTo(NewsEventItem item) {
        String newTitle = value(title, item.title());
        String newDescription = value(description, item.description());
        String newDate = value(date, item.date());
        String newKind = value(kind, item.kind());
        String normalized = newDate == null || newDate.isBlank()
                ? null
                : NewsEventQuery.normalizeDate(newDate);
        boolean complete = !newTitle.isBlank() && newDescription != null
                && !newDescription.isBlank() && newDate != null
                && !newDate.isBlank();
        String missing = complete ? null : missingFields(
                newTitle, newDescription, newDate);
        return new NewsEventItem(item.addressId(), newKind, newTitle,
                newDescription, item.url(), newDate, item.picture(),
                item.location(), item.executionId(), item.contentId(),
                !complete, missing, item.canonicalId(), normalized);
    }

    /**
     * The per-field old->new pairs this correction performs on
     * {@code item} (only the fields it actually changes), in a
     * stable order — the "what changed" of the trace entry.
     */
    public Map<String, String[]> changesOn(NewsEventItem item) {
        Map<String, String[]> changes = new LinkedHashMap<>();
        if (changes(title, item.title())) {
            changes.put("title", new String[] {item.title(), title.trim()});
        }
        if (changes(description, item.description())) {
            changes.put("description", new String[] {item.description(),
                    description.trim()});
        }
        if (changes(date, item.date())) {
            changes.put("date", new String[] {item.date(), date.trim()});
        }
        if (changes(kind, item.kind())) {
            changes.put("kind", new String[] {item.kind(), kind.trim()});
        }
        return changes;
    }

    private static boolean changes(String newValue, String old) {
        return newValue != null && !newValue.isBlank()
                && (old == null || !old.equals(newValue.trim()));
    }

    private static String value(String newValue, String old) {
        return newValue == null || newValue.isBlank()
                ? old : newValue.trim();
    }

    /** The missing-mandatory rendering (US-14.4's field order). */
    private static String missingFields(String title, String description,
                                        String date) {
        StringBuilder out = new StringBuilder();
        if (title == null || title.isBlank()) {
            out.append("title, ");
        }
        if (description == null || description.isBlank()) {
            out.append("description, ");
        }
        if (date == null || date.isBlank()) {
            out.append("date, ");
        }
        return out.length() == 0 ? null
                : out.substring(0, out.length() - 2);
    }
}
