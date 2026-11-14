package es.jllopez.neis.testsupport;

import es.jllopez.neis.domain.model.NewsEventItem;
import es.jllopez.neis.domain.model.NewsEventQuery;
import es.jllopez.neis.domain.model.NewsEventSighting;
import es.jllopez.neis.domain.port.NewsEventStorePort;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
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
     * sightings hang off the row whose canonical id is the same). */
    private final Map<String, List<NewsEventSighting>> sightingHistory =
            new HashMap<>();

    @Override
    public int saveAll(String addressId, String kind, List<NewsEventItem> items,
                       String executionId, String contentId) {
        provenanceCalls.add(new ProvenanceCall(executionId, contentId));
        long stamp = ++lastWrite;
        int stored = 0;
        for (NewsEventItem item : items) {
            String key = key(addressId, item);
            // The real connector normalizes blank urls to null (unique
            // index key) and evaluates completeness on save (US-14.4):
            // the double mirrors both so unit tests see the same rows.
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
                    item.canonicalId());
            store.put(key, row);
            // US-14.5 sighting: one per saveAll that produced the item,
            // carrying the same provenance (the real connector stores a
            // row per (item, execution, content) — the double's list
            // keeps them in order).
            sightingHistory.computeIfAbsent(row.canonicalId(),
                            k -> new ArrayList<>())
                    .add(new NewsEventSighting(row.canonicalId(), addressId,
                            contentId, executionId, Instant.now()));
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
                .filter(item -> query.dateInRange(item.date()))
                .limit(query.limit())
                .toList();
    }

    @Override
    public long count() {
        return store.size();
    }

    @Override
    public List<NewsEventSighting> sightings(String itemId) {
        return List.copyOf(sightingHistory.getOrDefault(itemId, List.of()));
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
