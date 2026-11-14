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
     */
    int saveAll(String addressId, String kind, List<NewsEventItem> items);

    /** The stored items of one address, newest first. */
    List<NewsEventItem> byAddress(String addressId);

    /** The total number of stored items (all addresses). */
    long count();
}
