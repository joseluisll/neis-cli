package es.jllopez.neis.domain.model;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * One news or event item discovered by selector-based extraction
 * (requirements US-12.2 / US-06.4 — extracted item data: title,
 * description, date and the detail URL that opens the item itself).
 *
 * <p>Mandatory metadata (US-14.4, issue #74): title, description and
 * date are the mandatory minimum — publication date for news, event
 * date for events (both carried by {@code date}). The store flags a
 * record missing any of them as {@code incomplete} with the missing
 * fields recorded (see {@link #missingMandatory()}), so selector or
 * source problems become visible instead of silently corrupting the
 * repository. Optional metadata ({@code picture}, {@code location})
 * is captured when present and may be absent.</p>
 *
 * <p>Provenance (US-14.2, read path US-14.3): {@code executionId} is
 * the execution that extracted the item and {@code contentId} the
 * scrape.html content record it came from. Both are nullable — rows
 * persisted before the provenance linking landed carry null. The
 * upsert refreshes them on every re-extraction (see
 * {@code NewsEventStorePort#saveAll}).</p>
 */
public record NewsEventItem(
        String addressId,
        String kind,
        String title,
        String description,
        String url,
        String date,
        /** Optional (US-14.4): the item's picture URL when the listing shows one. */
        String picture,
        /** Optional (US-14.4): the item's location/venue when the listing shows one. */
        String location,
        String executionId,
        String contentId,
        /** True when a mandatory field (title, description, date) is
         * missing — set by the store on save (US-14.4). */
        boolean incomplete,
        /** The missing mandatory fields, recorded so the gap is visible
         * (e.g. "description, date"); null when complete. */
        String missingFields,
        /** The deterministic dedup id (US-14.5): rows sharing it are
         * one logical record — the same story seen again (later
         * captures, paginated pages, other addresses). Set by the
         * store on save from {@link #canonicalKey()}; null on rows
         * persisted before V6. */
        String canonicalId) {

    /** Convenience constructor for extracted items: no provenance yet —
     * the store attaches it on {@code saveAll} (US-14.2). */
    public NewsEventItem(String addressId, String kind, String title,
                         String description, String url, String date) {
        this(addressId, kind, title, description, url, date,
                null, null, null, null, false, null, null);
    }

    /** Convenience constructor carrying US-14.2 provenance. */
    public NewsEventItem(String addressId, String kind, String title,
                         String description, String url, String date,
                         String executionId, String contentId) {
        this(addressId, kind, title, description, url, date,
                null, null, executionId, contentId, false, null, null);
    }

    /** Extracted item with the optional US-14.4 metadata (picture,
     * location); no provenance yet — the store attaches it. */
    public static NewsEventItem extracted(String addressId, String kind,
                                          String title, String description,
                                          String url, String date,
                                          String picture, String location) {
        return new NewsEventItem(addressId, kind, title, description, url, date,
                blankToNull(picture), blankToNull(location),
                null, null, false, null, null);
    }

    /**
     * The mandatory metadata (US-14.4) this item is missing: title,
     * description and date (publication date for news, event date for
     * events). Blank counts as missing — the extraction fallbacks
     * (empty link text, whitespace) must not pass as data.
     */
    public List<String> missingMandatory() {
        List<String> missing = new ArrayList<>();
        if (title == null || title.isBlank()) {
            missing.add("title");
        }
        if (description == null || description.isBlank()) {
            missing.add("description");
        }
        if (date == null || date.isBlank()) {
            missing.add("date");
        }
        return List.copyOf(missing);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /**
     * The deterministic identity key the canonical id (US-14.5) is
     * derived from — the same story seen again produces the same key
     * without any lookup:
     * <ul>
     *   <li>the item's stable identifier (its detail URL, fragment
     *   stripped) when it has one — pagination and later captures of
     *   the same listing reuse it;</li>
     *   <li>otherwise the similarity baseline: the normalized title +
     *   the leniently parsed date (ISO, so the extractor's human form
     *   and an ISO re-extraction hash the same), kind-agnostic — the
     *   same story may be classified differently by another source.</li>
     * </ul>
     * Shared by the real connector and the in-memory test double so
     * the canonical id cannot drift between them.
     */
    public String canonicalKey() {
        if (url != null && !url.isBlank()) {
            return "url:" + canonicalUrl(url);
        }
        LocalDate parsed = NewsEventQuery.parseLenient(date);
        String dateKey = parsed != null ? parsed.toString()
                : (date == null ? "" : date.trim());
        return "titdate:" + normalize(title) + "|" + dateKey;
    }

    /** The canonical dedup id (US-14.5): a deterministic hash of
     * {@link #canonicalKey()}. Equal keys, equal id. */
    public String canonicalId() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(
                    canonicalKey().getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder("cn-");
            for (int i = 0; i < 8; i++) {
                sb.append(String.format("%02x", hash[i]));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** URL normalization for identity: trimmed, fragment stripped, a
     * trailing slash dropped (the same story under /x and /x/). */
    private static String canonicalUrl(String url) {
        String trimmed = url.trim();
        int fragment = trimmed.indexOf('#');
        if (fragment >= 0) {
            trimmed = trimmed.substring(0, fragment);
        }
        while (trimmed.endsWith("/") && trimmed.length() > 1) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    /** Title normalization for the similarity baseline: trimmed,
     * lowercased, whitespace collapsed. Punctuation is kept — the
     * baseline is title + date, not a fuzzy match. */
    private static String normalize(String text) {
        if (text == null) {
            return "";
        }
        return text.trim().toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ");
    }
}
