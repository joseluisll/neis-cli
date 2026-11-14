package es.jllopez.neis.domain.model;

import java.util.ArrayList;
import java.util.List;

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
        String missingFields) {

    /** Convenience constructor for extracted items: no provenance yet —
     * the store attaches it on {@code saveAll} (US-14.2). */
    public NewsEventItem(String addressId, String kind, String title,
                         String description, String url, String date) {
        this(addressId, kind, title, description, url, date,
                null, null, null, null, false, null);
    }

    /** Convenience constructor carrying US-14.2 provenance. */
    public NewsEventItem(String addressId, String kind, String title,
                         String description, String url, String date,
                         String executionId, String contentId) {
        this(addressId, kind, title, description, url, date,
                null, null, executionId, contentId, false, null);
    }

    /** Extracted item with the optional US-14.4 metadata (picture,
     * location); no provenance yet — the store attaches it. */
    public static NewsEventItem extracted(String addressId, String kind,
                                          String title, String description,
                                          String url, String date,
                                          String picture, String location) {
        return new NewsEventItem(addressId, kind, title, description, url, date,
                blankToNull(picture), blankToNull(location),
                null, null, false, null);
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
}
