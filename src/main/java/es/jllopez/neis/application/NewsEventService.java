package es.jllopez.neis.application;

import es.jllopez.neis.domain.model.NewsEventItem;
import es.jllopez.neis.domain.model.NewsEventQuery;
import es.jllopez.neis.domain.model.NewsEventSighting;
import es.jllopez.neis.domain.port.ExecutionStorePort;
import es.jllopez.neis.domain.port.NewsEventStorePort;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Use case: querying the news/events repository (US-14, epic #54).
 * The US-14.2 provenance of every row — the execution that extracted
 * the item and the scrape.html content record it came from — becomes
 * observable here: {@link #provenanceGaps()} flags rows whose
 * references no longer resolve (news_events cannot carry FK columns
 * on execution_id/content_id — SQLite ALTER TABLE limitation — so the
 * single content-id formula discipline is verified, not enforced,
 * at the storage level; US-14.2 re-review finding).
 */
@Service
public class NewsEventService {

    /** One dangling provenance reference of a stored item. */
    public record ProvenanceGap(NewsEventItem item, String refType, String refId) {}

    private final NewsEventStorePort newsEventStore;
    private final ExecutionStorePort executionStore;
    private final ContentStoreService contentStore;

    public NewsEventService(NewsEventStorePort newsEventStore,
                            ExecutionStorePort executionStore,
                            ContentStoreService contentStore) {
        this.newsEventStore = newsEventStore;
        this.executionStore = executionStore;
        this.contentStore = contentStore;
    }

    /**
     * Every stored item whose provenance references a nonexistent
     * execution or content record. Rows without provenance (migrated
     * or pre-linking rows) are not gaps — they simply carry none.
     */
    public List<ProvenanceGap> provenanceGaps() {
        List<ProvenanceGap> gaps = new ArrayList<>();
        for (NewsEventItem item : newsEventStore.all()) {
            if (item.executionId() != null
                    && executionStore.findById(item.executionId()).isEmpty()) {
                gaps.add(new ProvenanceGap(item, "execution", item.executionId()));
            }
            if (item.contentId() != null
                    && contentStore.byId(item.contentId()).isEmpty()) {
                gaps.add(new ProvenanceGap(item, "content", item.contentId()));
            }
        }
        return gaps;
    }

    /** Every stored item of every address, newest first. */
    public List<NewsEventItem> all() {
        return newsEventStore.all();
    }

    /** The items matching the query, newest first (US-14.3). */
    public List<NewsEventItem> search(NewsEventQuery query) {
        return newsEventStore.search(query);
    }

    /** The total number of stored items. */
    public long count() {
        return newsEventStore.count();
    }

    /**
     * The appearance history of one logical record (US-14.5), looked
     * up by its canonical id: one entry per capture in which any row
     * of the record appeared, newest first. Re-encounters add
     * sightings, never records.
     */
    public List<NewsEventSighting> sightings(String canonicalId) {
        return newsEventStore.sightings(canonicalId);
    }

    /**
     * Full-text search over the repository's textual metadata (US-14.7):
     * terms match title/description/location, results are ranked
     * (storage-defined), and the US-14.3 filters combine on top.
     */
    public List<NewsEventItem> search(String text, NewsEventQuery filters) {
        return newsEventStore.search(text, filters);
    }
}
