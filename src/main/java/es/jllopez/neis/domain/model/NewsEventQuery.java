package es.jllopez.neis.domain.model;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * The filter criteria of a news/events repository search (US-14.3,
 * issue #57): every field is optional — null means "do not filter".
 *
 * <p>{@code dateFrom}/{@code dateTo} bound the item's own publication
 * date ({@link NewsEventItem#date()}). Dates are parsed leniently —
 * ISO-8601 ({@code 2026-11-01}) and the extractor's human format
 * ({@code Jul 16, 2026}) are both understood — so the range works on
 * the real extracted data, not only on ISO rows (verified live during
 * US-14.3: stored dates are human-formatted). Rows whose date is
 * missing or unparseable are excluded while a range is active.
 * {@code limit} caps the result size (values &lt;= 0 fall back to the
 * default of 20). Results are always newest first.</p>
 */
public record NewsEventQuery(
        String addressId,
        String kind,
        String dateFrom,
        String dateTo,
        int limit) {

    public static final int DEFAULT_LIMIT = 20;

    private static final DateTimeFormatter HUMAN =
            DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.ENGLISH);

    public NewsEventQuery {
        if (limit <= 0) {
            limit = DEFAULT_LIMIT;
        }
    }

    /** An unfiltered search: everything stored, newest first. */
    public static NewsEventQuery everything() {
        return new NewsEventQuery(null, null, null, null, DEFAULT_LIMIT);
    }

    /**
     * Whether the item date falls inside the query's range: true when
     * no bound is active; otherwise both the item date and the active
     * bounds are parsed (ISO first, then the extractor's human format)
     * and compared as dates. A missing or unparseable item date is
     * outside any active range.
     */
    public boolean dateInRange(String itemDate) {
        if ((dateFrom == null || dateFrom.isBlank())
                && (dateTo == null || dateTo.isBlank())) {
            return true;
        }
        LocalDate date = parse(itemDate);
        if (date == null) {
            return false;
        }
        LocalDate from = parse(dateFrom);
        LocalDate to = parse(dateTo);
        return (from == null || !date.isBefore(from))
                && (to == null || !date.isAfter(to));
    }

    private static LocalDate parse(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        try {
            return LocalDate.parse(trimmed);
        } catch (RuntimeException ignored) {
            // fall through to the extractor's human format
        }
        try {
            return LocalDate.parse(trimmed, HUMAN);
        } catch (RuntimeException ignored) {
            return null;
        }
    }
}
