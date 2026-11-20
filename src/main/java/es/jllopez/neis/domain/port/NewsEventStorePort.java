package es.jllopez.neis.domain.port;

import es.jllopez.neis.domain.model.NewsEventItem;
import es.jllopez.neis.domain.model.NewsEventQuery;
import es.jllopez.neis.domain.model.NewsEventSighting;

import java.util.List;
import java.util.Optional;

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
     *                    <p>A null item title does not abort the batch
     *                    on the schema's NOT NULL: it is stored empty
     *                    and flagged incomplete (missing: title) with
     *                    the rest of the batch (US-14.4 review). Note
     *                    that title-less (null or blank) link-less
     *                    items on one address share the title-based
     *                    natural key, so they upsert into ONE row.</p>
     */
    int saveAll(String addressId, String kind, List<NewsEventItem> items,
                String executionId, String contentId);

    /**
     * US-14.12 (issue #140): the CACHED-reuse variant — same upsert,
     * but a re-run over ALREADY-PROCESSED content records NO sighting
     * (the appearance history tracks real captures, not re-serves of
     * the same page). The connector and every double implement the
     * flag; false behaves exactly like the 5-arg form.
     */
    default int saveAll(String addressId, String kind, List<NewsEventItem> items,
                        String executionId, String contentId, boolean cachedReuse) {
        return saveAll(addressId, kind, items, executionId, contentId);
    }

    /** The stored items of one address, newest first. Items carry
     * their US-14.2 provenance (executionId/contentId; null on rows
     * persisted before the linking landed). */
    List<NewsEventItem> byAddress(String addressId);

    /** Every stored item of every address, newest first, provenance
     * included (repository browsing / the provenance check). */
    List<NewsEventItem> all();

    /**
     * The items matching the query, newest first (US-14.3): optional
     * filters by address and kind ("news"/"event") plus the item's own
     * publication date — parsed leniently by
     * {@link NewsEventQuery#dateInRange} (ISO and the extractor's human
     * form both work; missing or unparseable dates are excluded while a
     * range is active) — capped at the query's limit. Provenance is
     * included.
     */
    List<NewsEventItem> search(NewsEventQuery query);

    /** The total number of stored items (all addresses). */
    long count();

    /**
     * The number of items FIRST stored at or after the instant
     * (US-16.8: the dashboard's repository-growth delta — growth is
     * visible without mental arithmetic over the single total). The
     * timestamp is the row's created_at (first storage), not its last
     * update: re-extraction upserts do not inflate the delta.
     */
    long countCreatedSince(java.time.Instant since);

    /**
     * The appearance history of one logical record (US-14.5), looked
     * up by its canonical id: one entry per capture in which any row
     * of the record appeared, newest first, each with its US-14.2
     * provenance. Re-encounters (later captures, paginated pages,
     * other addresses) add sightings — never records.
     */
    List<NewsEventSighting> sightings(String canonicalId);

    /**
     * Full-text search over the repository's textual metadata (US-14.7,
     * issue #85): the terms match against title, description and
     * location, results come RANKED (most relevant first — the ranking
     * is the storage's, not recency), and the US-14.3 filters (kind,
     * address, lenient date range) plus the limit combine on top.
     */
    List<NewsEventItem> search(String text, NewsEventQuery filters);

    /**
     * ONE representative row of the logical record with this canonical
     * id (US-14.5), for the item-detail lookups (MCP neis_get_news_item).
     * The row set of a canonical id is the record's history — the
     * sightings query returns that; this returns the newest row itself.
     */
    Optional<NewsEventItem> byCanonicalId(String canonicalId);
}
