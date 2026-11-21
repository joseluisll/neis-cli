package es.jllopez.neis.domain.model;

import java.util.List;

/**
 * The selection criteria of a BULK prune (US-14.13, issue #146):
 * every field is optional — a null/blank field means "do not select
 * by it". The criteria combine as a UNION of two selection modes:
 *
 * <ul>
 * <li><b>Query mode</b> — any of {@code addressId}, {@code kind},
 * {@code dateFrom}, {@code dateTo}, {@code terms} or a completeness
 * other than {@code all} selects through the SAME query path the
 * listing uses ({@code search(text, filters)} / {@code search(query)};
 * never a client-side filter over {@code listAll()}).</li>
 * <li><b>Explicit mode</b> — {@code canonicalIds} names the logical
 * records directly; each prunes as a whole canonical group (every row
 * sharing the id), per the US-14.5 model. Unknown ids are reported
 * back, never silently dropped.</li>
 * </ul>
 *
 * <p>{@code reason} rides every pruned row (US-14.10 semantics); it is
 * required at confirm time and validated by the service, not here —
 * a dry run may omit it. Bulk prune is always a SOFT delete: pruned
 * rows stay with their provenance readable, and re-extraction never
 * resurrects them.</p>
 */
public record BulkPruneSelection(
        String addressId,
        String kind,
        String dateFrom,
        String dateTo,
        String terms,
        String completeness,
        List<String> canonicalIds,
        String reason) {

    public BulkPruneSelection {
        addressId = blankToNull(addressId);
        kind = blankToNull(kind) == null ? null
                : kind.trim().toLowerCase(java.util.Locale.ROOT);
        dateFrom = blankToNull(dateFrom);
        dateTo = blankToNull(dateTo);
        terms = blankToNull(terms);
        completeness = blankToNull(completeness) == null ? "all"
                : completeness.trim().toLowerCase(java.util.Locale.ROOT);
        canonicalIds = canonicalIds == null ? List.of()
                : canonicalIds.stream()
                        .filter(id -> id != null && !id.isBlank())
                        .map(String::trim)
                        .toList();
        reason = blankToNull(reason);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    /** Whether any query-mode criterion is active. */
    public boolean hasQueryCriteria() {
        return addressId != null || kind != null || dateFrom != null
                || dateTo != null || terms != null
                || !"all".equals(completeness);
    }

    /** Whether the selection names anything at all. */
    public boolean isEmpty() {
        return !hasQueryCriteria() && canonicalIds.isEmpty();
    }
}
