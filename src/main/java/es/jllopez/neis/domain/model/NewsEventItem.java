package es.jllopez.neis.domain.model;

/**
 * One news or event item discovered by selector-based extraction
 * (requirements US-12.2 / US-06.4 — extracted item data: title,
 * description, date and the detail URL that opens the item itself).
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
        String executionId,
        String contentId) {

    /** Convenience constructor for extracted items: no provenance yet —
     * the store attaches it on {@code saveAll} (US-14.2). */
    public NewsEventItem(String addressId, String kind, String title,
                         String description, String url, String date) {
        this(addressId, kind, title, description, url, date, null, null);
    }
}
