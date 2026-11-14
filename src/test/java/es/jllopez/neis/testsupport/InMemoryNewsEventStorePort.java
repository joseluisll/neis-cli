package es.jllopez.neis.testsupport;

import es.jllopez.neis.domain.model.NewsEventItem;
import es.jllopez.neis.domain.port.NewsEventStorePort;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** In-memory news/events store for unit tests: the same deterministic
 * (address, url — or title) upsert semantics as the real connector,
 * the same kind fallback (the parameter wins; the item kind is the
 * fallback) and the same read contract — newest first, where a
 * re-upserted row is the newest (the real connector orders by
 * updated_at DESC; a sequence stamp stands in for the timestamp). */
public class InMemoryNewsEventStorePort implements NewsEventStorePort {

    private final Map<String, NewsEventItem> store = new LinkedHashMap<>();
    /** Per-key write sequence: higher = written later = newer. */
    private final Map<String, Long> sequence = new HashMap<>();
    private long lastWrite = 0;
    /** One provenance pair per saveAll call (US-14.2, exact-field assertions). */
    public record ProvenanceCall(String executionId, String contentId) {}
    public final List<ProvenanceCall> provenanceCalls = new ArrayList<>();

    @Override
    public int saveAll(String addressId, String kind, List<NewsEventItem> items,
                       String executionId, String contentId) {
        provenanceCalls.add(new ProvenanceCall(executionId, contentId));
        int stored = 0;
        for (NewsEventItem item : items) {
            String key = key(addressId, item);
            store.put(key,
                    new NewsEventItem(addressId,
                            kind == null ? item.kind() : kind,
                            item.title(), item.description(), item.url(),
                            item.date(), executionId, contentId));
            sequence.put(key, ++lastWrite);
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
    public long count() {
        return store.size();
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
