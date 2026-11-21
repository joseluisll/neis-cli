package es.jllopez.neis.application;

import es.jllopez.neis.domain.model.BulkPruneSelection;
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

    /** The items first stored at or after the instant (US-16.8 growth
     * delta): first storage, not the last upsert. */
    public long countCreatedSince(java.time.Instant since) {
        return newsEventStore.countCreatedSince(since);
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

    /**
     * ONE representative row of the logical record (US-14.5), for the
     * item-detail surfaces (MCP neis_get_news_item): pair it with
     * {@link #sightings} for the full history.
     */
    public java.util.Optional<NewsEventItem> item(String canonicalId) {
        return newsEventStore.byCanonicalId(canonicalId);
    }

    /**
     * The manual correction of a logical record (US-14.10, issue
     * #124): traceable — the store records what changed, when and by
     * whom — and canonical-identity-preserving. The actor is the CLI
     * operator; remote callers (a later slice) will carry their own.
     */
    public int correct(String canonicalId,
                       es.jllopez.neis.domain.model.NewsItemCorrection correction,
                       String actor) {
        return newsEventStore.correct(canonicalId, correction, actor);
    }

    /** The prune of a logical record (US-14.10): soft-delete, excluded
     * from list/search/export, provenance remains readable. */
    public int prune(String canonicalId, String reason) {
        return newsEventStore.prune(canonicalId, reason);
    }

    /**
     * The dry-run view of a bulk prune (US-14.13, issue #146): the
     * records the selection currently matches, WITHOUT writing
     * anything. The match reuses the listing's query path (the same
     * {@code search} the REST twin and the {@code news list} command
     * run), never a client-side filter over {@link #all()}.
     */
    public BulkPrunePreview bulkPrunePreview(BulkPruneSelection selection) {
        ResolvedSelection resolved = resolveSelection(selection);
        return new BulkPrunePreview(
                List.copyOf(resolved.matches()), resolved.unknownIds());
    }

    /**
     * The CONFIRMED bulk prune (US-14.13): soft-deletes every record
     * the selection matches, as whole canonical groups (US-14.10
     * semantics — the reason rides every affected row, provenance
     * stays readable, re-extraction never resurrects). Zero matches
     * are a no-op success; an empty selection or a blank reason at
     * confirm time is refused.
     */
    public BulkPruneOutcome bulkPrune(BulkPruneSelection selection) {
        if (selection.reason() == null) {
            throw new IllegalArgumentException(
                    "A bulk prune needs a reason — it is recorded on "
                            + "every pruned row");
        }
        if (selection.isEmpty()) {
            throw new IllegalArgumentException(
                    "Empty bulk-prune selection — give at least one "
                            + "criterion (address, kind, date range, terms, "
                            + "completeness or explicit canonical ids)");
        }
        ResolvedSelection resolved = resolveSelection(selection);
        int records = 0;
        int rows = 0;
        for (NewsEventItem item : resolved.matches()) {
            int pruned = newsEventStore.prune(item.canonicalId(),
                    selection.reason());
            if (pruned > 0) {
                records++;
                rows += pruned;
            }
        }
        return new BulkPruneOutcome(records, rows, resolved.unknownIds());
    }

    /** The container of one selection resolution: the matched records
     * (newest first, canonical groups deduplicated) plus the explicit
     * ids that resolved to nothing. */
    private record ResolvedSelection(List<NewsEventItem> matches,
            List<String> unknownIds) {}

    /**
     * The confirmed-prune result: how many logical records (canonical
     * groups) and how many physical rows the prune touched.
     */
    public record BulkPruneOutcome(int prunedRecords, int prunedRows,
            List<String> unknownCanonicalIds) {}

    /**
     * The dry-run result: the matched records (newest first) and the
     * selection they came from, so every surface renders the same
     * preview. Never carries a write.
     */
    public record BulkPrunePreview(List<NewsEventItem> items,
            List<String> unknownCanonicalIds) {

        public int matchedRecords() {
            return items.size();
        }
    }

    /**
     * The UNION of both selection modes, newest first: the query-mode
     * matches (through the listing's search path, completeness
     * filtered on top) plus every explicit canonical id that resolves.
     * Explicit ids already inside the query matches collapse into one
     * record — one canonical id, one logical record.
     */
    private ResolvedSelection resolveSelection(BulkPruneSelection sel) {
        List<NewsEventItem> matches = new ArrayList<>();
        if (sel.hasQueryCriteria()) {
            validateBounds(sel);
            var query = new NewsEventQuery(sel.addressId(), sel.kind(),
                    sel.dateFrom(), sel.dateTo(), Integer.MAX_VALUE);
            if (sel.terms() != null) {
                matches.addAll(newsEventStore.search(sel.terms(), query));
            } else {
                matches.addAll(newsEventStore.search(query));
            }
            filterByCompleteness(matches, sel.completeness());
        }
        List<String> unknown = new ArrayList<>();
        java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<>();
        for (NewsEventItem item : matches) {
            seen.add(item.canonicalId());
        }
        for (String id : sel.canonicalIds()) {
            var record = newsEventStore.byCanonicalId(id);
            if (record.isEmpty()) {
                unknown.add(id);
            } else if (!seen.contains(id)) {
                matches.add(record.get());
                seen.add(id);
            }
        }
        return new ResolvedSelection(matches, List.copyOf(unknown));
    }

    private static void validateBounds(BulkPruneSelection sel) {
        // a typo in a range bound must not silently deactivate the
        // filter (the US-14.3 review rule the CLI list command already
        // applies) — same lenient parser, same refusal
        for (String[] bound : new String[][] {{"dateFrom", sel.dateFrom()},
                {"dateTo", sel.dateTo()}}) {
            if (bound[1] != null
                    && NewsEventQuery.parseLenient(bound[1]) == null) {
                throw new IllegalArgumentException("Invalid " + bound[0]
                        + " '" + bound[1] + "' — use ISO (yyyy-MM-dd) or "
                        + "the extractor's form (e.g. 'Jul 16, 2026')");
            }
        }
    }

    private static void filterByCompleteness(List<NewsEventItem> items,
            String completeness) {
        boolean filterActive = !"all".equals(completeness);
        boolean wantIncomplete = "incomplete".equals(completeness);
        if (!filterActive) {
            return;
        }
        if (!wantIncomplete && !"complete".equals(completeness)) {
            throw new IllegalArgumentException("Invalid completeness '"
                    + completeness + "' — use all, complete or incomplete");
        }
        items.removeIf(item -> wantIncomplete != item.incomplete());
    }

    /** The stored correction trace of a logical record (US-14.10). */
    public List<String> correctionTrace(String canonicalId) {
        return newsEventStore.correctionTrace(canonicalId);
    }
}
