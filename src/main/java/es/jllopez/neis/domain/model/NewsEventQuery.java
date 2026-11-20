package es.jllopez.neis.domain.model;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * The filter criteria of a news/events repository search (US-14.3,
 * issue #57): every field is optional — null means "do not filter".
 *
 * <p>{@code dateFrom}/{@code dateTo} bound the item's own publication
 * date. Since US-14.9 (#123) the store carries a NORMALIZED ISO-8601
 * date per item ({@link NewsEventItem#dateNormalized()}) and the range
 * compares against THAT value — never lexically against the raw text.
 * The range bounds themselves are parsed leniently (ISO-8601
 * {@code 2026-11-01} and the extractor's human format {@code Jul 16,
 * 2026} are both understood). A legacy row without a normalized value
 * (not yet backfilled) falls back to the lenient parse of its raw
 * date, so an un-backfilled store keeps working. Rows whose date is
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

    /** The legacy long form ('July 4, 2025') found in the real stored
     * data — the same pattern with the FULL month name (US-14.9
     * backfill live evidence). */
    private static final DateTimeFormatter HUMAN_LONG =
            DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.ENGLISH);

    /** The day-first full-month form ('5 November 2026') found in the
     * real stored data (US-14.9 backfill live evidence, the dominant
     * unparseable family). */
    private static final DateTimeFormatter HUMAN_DAY_FIRST =
            DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH);

    /** The day-first abbreviated form ('6 Jul 2026') — the same shape
     * with the short month name. */
    private static final DateTimeFormatter HUMAN_DAY_FIRST_SHORT =
            DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);

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
     * Whether the item falls inside the query's range (US-14.9): the
     * comparison runs against the item's NORMALIZED ISO date when it
     * has one; a legacy row without a normalized value falls back to
     * the lenient parse of its raw date. True when no bound is active;
     * a missing or unparseable date is outside any active range.
     */
    public boolean dateInRange(NewsEventItem item) {
        if ((dateFrom == null || dateFrom.isBlank())
                && (dateTo == null || dateTo.isBlank())) {
            return true;
        }
        if (item == null) {
            return false;
        }
        if (item.dateNormalized() != null) {
            LocalDate date;
            try {
                date = LocalDate.parse(item.dateNormalized());
            } catch (RuntimeException e) {
                return false;
            }
            return withinBounds(date);
        }
        return dateInRange(item.date());
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
        return withinBounds(date);
    }

    private boolean withinBounds(LocalDate date) {
        LocalDate from = parse(dateFrom);
        LocalDate to = parse(dateTo);
        return (from == null || !date.isBefore(from))
                && (to == null || !date.isAfter(to));
    }

    /**
     * The lenient item-date parser (ISO first, then the extractor's
     * human format), or null when the value does not parse. Public so
     * the driving adapter can validate user-supplied range bounds
     * instead of letting a typo silently deactivate a filter.
     */
    public static LocalDate parseLenient(String value) {
        return parse(value);
    }

    /**
     * The ONE shared date normalizer (US-14.9, issue #123): the raw
     * extracted date as a machine-comparable ISO-8601 string
     * ({@code yyyy-MM-dd}), or null when it does not parse. The store
     * connector, the in-memory test double and the legacy-row backfill
     * all call THIS method — no second parser may exist anywhere, so
     * a capture and a backfill of the same text always agree.
     */
    public static String normalizeDate(String raw) {
        LocalDate parsed = parse(raw);
        return parsed == null ? null : parsed.toString();
    }

    /**
     * The US-14.11 (issue #135) publication-date fallback: a
     * URL-embedded timestamp — the ABC-style {@code YYYYMMDDHHMMSS}
     * run inside the article URL (e.g.
     * {@code ...-20261119222527-nt.html}) — as the NORMALIZED ISO
     * date, or null when the URL carries no plausible timestamp. The
     * strict parse rejects digit runs that are not a real date-time
     * (month 13, day 32, ...); part of the ONE domain date toolkit,
     * so the connector and the test double cannot drift. Only a full
     * 14-digit run counts — a shorter digit run (an 8-digit article
     * id, say) is not a timestamp.
     */
    public static String dateFromUrl(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        java.time.format.DateTimeFormatter strict =
                java.time.format.DateTimeFormatter
                        // 'uuuu' (proleptic year): STRICT resolution
                        // rejects year-of-era without an era context.
                        .ofPattern("uuuuMMddHHmmss")
                        .withResolverStyle(
                                java.time.format.ResolverStyle.STRICT);
        java.util.regex.Matcher m = URL_TIMESTAMP.matcher(url);
        while (m.find()) {
            try {
                return java.time.LocalDateTime
                        .parse(m.group(), strict)
                        .toLocalDate().toString();
            } catch (RuntimeException ignored) {
                // a 14-digit run that is not a plausible date-time:
                // keep scanning (the next run may be the timestamp)
            }
        }
        return null;
    }

    /** Exactly 14 digits — the ABC-style URL-embedded timestamp shape. */
    private static final java.util.regex.Pattern URL_TIMESTAMP =
            java.util.regex.Pattern.compile("\\d{14}");

    private static LocalDate parse(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        try {
            return LocalDate.parse(trimmed);
        } catch (RuntimeException ignored) {
            // fall through to the human formats below
        }
        // An ISO DATETIME ('2026-09-29T12:00:00Z') carries its date in
        // UTC — the natural publication date of the raw text (US-14.9
        // backfill live evidence).
        try {
            return java.time.Instant.parse(trimmed)
                    .atZone(java.time.ZoneOffset.UTC).toLocalDate();
        } catch (RuntimeException ignored) {
            // fall through to the human formats below
        }
        try {
            return LocalDate.parse(trimmed, HUMAN);
        } catch (RuntimeException ignored) {
            // fall through to the long month-name form
        }
        try {
            return LocalDate.parse(trimmed, HUMAN_LONG);
        } catch (RuntimeException ignored) {
            // fall through to the day-first forms
        }
        try {
            return LocalDate.parse(trimmed, HUMAN_DAY_FIRST);
        } catch (RuntimeException ignored) {
            // fall through to the day-first abbreviated form
        }
        try {
            return LocalDate.parse(trimmed, HUMAN_DAY_FIRST_SHORT);
        } catch (RuntimeException ignored) {
            return null;
        }
    }
}
