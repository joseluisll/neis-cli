package es.jllopez.neis.testsupport;

import es.jllopez.neis.domain.port.SettingsStorePort;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** In-memory double of the settings store (V13): the same contract the
 * persistence connector documents — upsert on save, delete is a no-op
 * when absent, reads see the current overrides. */
public class InMemorySettingsStorePort implements SettingsStorePort {

    private final Map<String, String> store = new LinkedHashMap<>();

    @Override
    public Map<String, String> all() {
        return new LinkedHashMap<>(store);
    }

    @Override
    public Optional<String> find(String key) {
        return Optional.ofNullable(store.get(key));
    }

    @Override
    public void save(String key, String value) {
        store.put(key, value);
    }

    @Override
    public void delete(String key) {
        store.remove(key);
    }
}
