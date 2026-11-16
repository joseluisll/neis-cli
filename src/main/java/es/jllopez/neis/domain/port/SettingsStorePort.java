package es.jllopez.neis.domain.port;

import java.util.Map;
import java.util.Optional;

/**
 * Store port for persisted configuration overrides (US-16.5, issue #97
 * review finding): the management UI's edit path validates a value
 * against the key's allowlist rule and stores it here; the stored
 * overrides layer over the externalized configuration (US-15.1,
 * application.yml stays the source of record — the override wins).
 * The overrides are applied at every boot (after the consumers are
 * wired) and immediately on edit from the management UI.
 */
public interface SettingsStorePort {

    /** All stored overrides, key -> value. */
    Map<String, String> all();

    /** The stored override of one key, or empty. */
    Optional<String> find(String key);

    /** Persists (upserts) the override of one key. */
    void save(String key, String value);

    /** Removes the override of one key (no-op when absent). */
    void delete(String key);
}
