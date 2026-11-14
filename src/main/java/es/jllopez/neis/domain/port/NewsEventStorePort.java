package es.jllopez.neis.domain.port;

import es.jllopez.neis.domain.model.NewsEventItem;

import java.util.List;

/**
 * Port for the news/events repository (US-14.1, issue #55): extracted
 * items are persisted as entities — not only as content files — with
 * all the metadata the extraction produced. The model distinguishes
 * news from events through {@link NewsEventItem#kind()}.
 *
 * <p>Idempotent by design (US-15.4): the storage key is deterministic
 * per (address, item detail URL — or title when the item has no link),
 * so re-extraction updates the same rows instead of duplicating them.</p>
 */
public interface NewsEventStorePort {

    /**
     * Upserts the extracted items of one address. Returns the number of
     * records stored (new + updated).
     *
     * @param executionId the execution that extracted the items
     *                    (US-14.2 provenance; nullable)
     * @param contentId   the scrape.html content record the items came
     *                    from (US-14.2 provenance; nullable)
     *                    <p>Provenance is REFRESHED on every upsert: a
     *                    re-run under a new execution id relinks the
     *                    rows — and a null provenance on a later save
     *                    would overwrite existing provenance with NULL
     *                    (no production caller does; pinned here per
     *                    the US-14.2 review).</p>
     */
    int saveAll(String addressId, String kind, List<NewsEventItem> items,
                String executionId, String contentId);

    /** The stored items of one address, newest first. Items carry
     * their US-14.2 provenance (executionId/contentId; null on rows
     * persisted before the linking landed). */
    List<NewsEventItem> byAddress(String addressId);

    /** Every stored item of every address, newest first, provenance
     * included (repository browsing / the provenance check). */
    List<NewsEventItem> all();

    /** The total number of stored items (all addresses). */
    long count();
}
