package es.jllopez.neis.testsupport;

import es.jllopez.neis.domain.model.NewsEventItem;
import es.jllopez.neis.domain.port.NewsEventStorePort;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** In-memory news/events store for unit tests: the same deterministic
 * (address, url — or title) upsert semantics as the real connector. */
public class InMemoryNewsEventStorePort implements NewsEventStorePort {

    private final Map<String, NewsEventItem> store = new LinkedHashMap<>();
    /** Provenance recorded by saveAll (US-14.2, test assertions). */
    public final List<String> provenanceCalls = new ArrayList<>();

    @Override
    public int saveAll(String addressId, String kind, List<NewsEventItem> items,
                       String executionId, String contentId) {
        provenanceCalls.add(executionId + "|" + contentId);
        int stored = 0;
        for (NewsEventItem item : items) {
            store.put(key(addressId, item),
                    new NewsEventItem(addressId, kind, item.title(),
                            item.description(), item.url(), item.date()));
            stored++;
        }
        return stored;
    }

    @Override
    public List<NewsEventItem> byAddress(String addressId) {
        return store.entrySet().stream()
                .filter(e -> e.getKey().startsWith(addressId + "|"))
                .map(Map.Entry::getValue)
                .toList();
    }

    @Override
    public long count() {
        return store.size();
    }

    private static String key(String addressId, NewsEventItem item) {
        return addressId + "|"
                + (item.url() != null && !item.url().isBlank()
                        ? item.url().trim()
                        : "title:" + (item.title() == null ? "" : item.title().trim()));
    }

    /** All stored items (test inspection). */
    public List<NewsEventItem> all() {
        return new ArrayList<>(store.values());
    }
}
