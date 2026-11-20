package es.jllopez.neis.testsupport;

import es.jllopez.neis.domain.model.NewsEventItem;
import es.jllopez.neis.domain.model.NewsEventQuery;
import es.jllopez.neis.domain.model.NewsEventSighting;
import es.jllopez.neis.domain.port.NewsEventStorePort;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.time.Instant;

/** In-memory news/events store for unit tests: the same deterministic
 * (address, url — or title) upsert semantics as the real connector,
 * the same kind fallback (the parameter wins; the item kind is the
 * fallback) and the same read contract — newest first, where a
 * re-upserted row is the newest (the real connector orders by
 * updated_at DESC; a sequence stamp stands in for the timestamp). */
public class InMemoryNewsEventStorePort implements NewsEventStorePort {

    private final Map<String, NewsEventItem> store = new LinkedHashMap<>();
    /** Per-key write sequence: higher = written later = newer. Rows of
     * ONE saveAll call share the stamp (like the connector, which
     * writes one updated_at per call) — within a batch the insertion
     * order stands in for the real store's arbitrary tiebreak. */
    private final Map<String, Long> sequence = new HashMap<>();
    private long lastWrite = 0;
    /** One provenance pair per saveAll call (US-14.2, exact-field assertions). */
    public record ProvenanceCall(String executionId, String contentId) {}
    public final List<ProvenanceCall> provenanceCalls = new ArrayList<>();
    /** In-memory sighting history (US-14.5): keyed by canonical id —
     * the double's rows have no row ids, so its {@link #sightings}
     * lookup is by canonical id (a faithful stand-in: the real store's
     * sightings hang off the row whose canonical id is the same).
     * Insertion-ordered maps: sightings() returns them NEWEST FIRST
     * (reversed), and an identical (execution, content) sighting is
     * idempotent like the real store's deterministic sighting id. */
    private final Map<String, Map<String, NewsEventSighting>> sightingHistory =
            new HashMap<>();
    /** First-storage timestamps (US-16.8 growth delta): a re-upserted
     * row keeps its first stamp, like the real store's created_at. */
    private final Map<String, Instant> firstCreatedAt = new HashMap<>();

    @Override
    public int saveAll(String addressId, String kind, List<NewsEventItem> items,
                       String executionId, String contentId) {
        provenanceCalls.add(new ProvenanceCall(executionId, contentId));
        long stamp = ++lastWrite;
        int stored = 0;
        for (NewsEventItem item : items) {
            String key = key(addressId, item);
            List<String> missing = item.missingMandatory();
            // The real connector normalizes blank urls to null (unique
            // index key) and evaluates completeness on save (US-14.4):
            // the double mirrors both — plus the US-14.9 normalized
            // date computed through the ONE shared domain normalizer —
            // so unit tests see the same rows.
            String url = item.url() == null || item.url().isBlank()
                    ? null : item.url();
            NewsEventItem row = new NewsEventItem(addressId,
                    kind == null ? item.kind() : kind,
                    item.title() == null ? "" : item.title(),
                    item.description(), url,
                    item.date(), item.picture(), item.location(),
                    executionId, contentId,
                    !missing.isEmpty(),
                    missing.isEmpty() ? null : String.join(", ", missing),
                    item.computedCanonicalId(),
                    NewsEventQuery.normalizeDate(item.date()));
            store.put(key, row);
            firstCreatedAt.putIfAbsent(key, Instant.now());
            // US-14.5 sighting: one per saveAll that produced the item,
            // carrying the same provenance — idempotent per (execution,
            // content) triple like the real store's deterministic id.
            sightingHistory
                    .computeIfAbsent(row.canonicalId(), k -> new LinkedHashMap<>())
                    .putIfAbsent(addressId + "|" + executionId + "|" + contentId,
                            new NewsEventSighting(row.canonicalId(), addressId,
                                    contentId, executionId, Instant.now()));
            sequence.put(key, stamp);
            stored++;
        }
        return stored;
    }

    /**
     * US-14.12 (mirrors the real connector): a CACHED reuse upserts
     * the rows but records NO sighting — the appearance history
     * tracks real captures, never re-serves of the same page.
     */
    @Override
    public int saveAll(String addressId, String kind, List<NewsEventItem> items,
                       String executionId, String contentId, boolean cachedReuse) {
        if (!cachedReuse) {
            return saveAll(addressId, kind, items, executionId, contentId);
        }
        provenanceCalls.add(new ProvenanceCall(executionId, contentId));
        long stamp = ++lastWrite;
        int stored = 0;
        for (NewsEventItem item : items) {
            String key = key(addressId, item);
            String url = item.url() == null || item.url().isBlank()
                    ? null : item.url();
            List<String> missing = item.missingMandatory();
            NewsEventItem row = new NewsEventItem(addressId,
                    kind == null ? item.kind() : kind,
                    item.title() == null ? "" : item.title(),
                    item.description(), url,
                    item.date(), item.picture(), item.location(),
                    executionId, contentId,
                    !missing.isEmpty(),
                    missing.isEmpty() ? null : String.join(", ", missing),
                    item.computedCanonicalId(),
                    NewsEventQuery.normalizeDate(item.date()));
            store.put(key, row);
            firstCreatedAt.putIfAbsent(key, Instant.now());
            // NO sighting: the US-14.12 cached-reuse semantics.
            sequence.put(key, stamp);
            stored++;
        }
        return stored;
    }

    @Override
    public List<NewsEventItem> byAddress(String addressId) {
        return newestFirst(store.entrySet().stream()
                .filter(e -> e.getKey().startsWith(addressId + "|")));
    }

    @Override
    public List<NewsEventItem> all() {
        return newestFirst(store.entrySet().stream());
    }

    @Override
    public List<NewsEventItem> search(NewsEventQuery query) {
        // The kind filter mirrors the connector's SQL exactly: exact,
        // case-sensitive equality on the (already normalized) value.
        return newestFirst(store.entrySet().stream()
                .filter(e -> query.addressId() == null || query.addressId().isBlank()
                        || e.getKey().startsWith(query.addressId().trim() + "|")))
                .stream()
                .filter(item -> query.kind() == null || query.kind().isBlank()
                        || query.kind().equals(item.kind()))
                .filter(item -> query.dateInRange(item))
                .limit(query.limit())
                .toList();
    }

    @Override
    public long count() {
        return store.size();
    }

    @Override
    public long countCreatedSince(java.time.Instant since) {
        // First-storage timestamps (created_at) stand in for the real
        // store's column: a re-upserted row keeps its first stamp, like
        // the connector's created_at.
        return store.keySet().stream()
                .filter(key -> !firstCreatedAt
                        .getOrDefault(key, Instant.MAX).isBefore(since))
                .count();
    }

    @Override
    public List<NewsEventSighting> sightings(String itemId) {
        // Newest first, like the real store.
        List<NewsEventSighting> stored = new ArrayList<>(
                sightingHistory.getOrDefault(itemId, Map.of()).values());
        java.util.Collections.reverse(stored);
        return List.copyOf(stored);
    }

    @Override
    public java.util.Optional<NewsEventItem> byCanonicalId(String canonicalId) {
        // Newest row of the logical record (same insertion-order
        // stand-in for recency as the other queries).
        return store.values().stream()
                .filter(i -> canonicalId.equals(i.canonicalId()))
                .reduce((first, second) -> second);
    }

    @Override
    public List<NewsEventItem> search(String text, NewsEventQuery filters) {
        // Double fidelity note (US-14.7): the real store matches via
        // SQLite FTS5 (token-based, ranked by bm25); the double matches
        // whole-term substrings over title/description/location and
        // returns hits NEWEST FIRST — no ranking. Term splitting and
        // the filter/limit semantics are the same.
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<String> terms = java.util.Arrays.stream(text.trim().split("\\s+"))
                .map(t -> t.toLowerCase(Locale.ROOT))
                .toList();
        return newestFirst(store.entrySet().stream()
                .filter(e -> {
                    NewsEventItem item = e.getValue();
                    String haystack = (nullSafe(item.title()) + " "
                            + nullSafe(item.description()) + " "
                            + nullSafe(item.location())).toLowerCase(Locale.ROOT);
                    return terms.stream().allMatch(haystack::contains);
                }))
                .stream()
                .filter(item -> filters.addressId() == null
                        || filters.addressId().isBlank()
                        || filters.addressId().trim().equals(item.addressId()))
                .filter(item -> filters.kind() == null || filters.kind().isBlank()
                        || filters.kind().equals(item.kind()))
                .filter(item -> filters.dateInRange(item))
                .limit(filters.limit())
                .toList();
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }

    private List<NewsEventItem> newestFirst(
            java.util.stream.Stream<Map.Entry<String, NewsEventItem>> entries) {
        return entries.sorted((a, b) -> Long.compare(
                        sequence.getOrDefault(b.getKey(), 0L),
                        sequence.getOrDefault(a.getKey(), 0L)))
                .map(Map.Entry::getValue)
                .toList();
    }

    private static String key(String addressId, NewsEventItem item) {
        return addressId + "|"
                + (item.url() != null && !item.url().isBlank()
                        ? item.url().trim()
                        : "title:" + (item.title() == null ? "" : item.title().trim()));
    }
}
