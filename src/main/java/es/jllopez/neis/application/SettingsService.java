package es.jllopez.neis.application;

import es.jllopez.neis.domain.port.SettingsStorePort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Use case: the editable configuration (US-16.5, issue #97 review
 * finding — the section was view-only while the requirement says
 * "viewed <b>and edited</b>"). A small ALLOWLIST of runtime-safe keys
 * is editable: each candidate value is validated against the key's
 * rule BEFORE saving (a typo is refused, never stored), the stored
 * override layers over the externalized configuration (US-15.1,
 * application.yml stays the source of record — the override wins), and
 * the callers apply the change to the live consumers right after a
 * successful save (see the infrastructure override applier, which also
 * applies every stored override at boot).
 *
 * <p>Scope of the allowlist (owner decision recorded on #97 — the
 * clarify timed out, the recommended persisted-overrides option was
 * taken): only keys whose consumers read the value at USE time are
 * editable, so an edit takes effect immediately in the running
 * process. The AI provider settings and the boot-critical paths
 * (database, log, work dir) are bound at boot or rebuilt with the
 * context — they stay read-only here and are edited in
 * application.yml; pretending to edit them live would be dishonest.</p>
 */
@Service
public class SettingsService {

    private static final Logger log = LoggerFactory.getLogger(SettingsService.class);

    /** The scrape-connector routing mode (see ScrapeConnectorRouter). */
    public static final String SCRAPE_CONNECTOR = "neis.scrape.connector";
    /** The global extraction cap (US-12.5); blank/unset = no limit. */
    public static final String EXTRACTION_MAX_ITEMS = "neis.extraction.max-items";
    /** The address-verification probe timeout, seconds. */
    public static final String VERIFICATION_TIMEOUT = "neis.address.verification.timeout-seconds";

    /** The editable keys, in display order. */
    public static final java.util.List<String> EDITABLE_KEYS =
            java.util.List.of(SCRAPE_CONNECTOR, EXTRACTION_MAX_ITEMS, VERIFICATION_TIMEOUT);

    private final SettingsStorePort store;
    /** The live layered configuration (system properties over
     * application.yml) — the source of the un-overridden effective
     * values (US-16.7: system properties alone do NOT carry the YAML
     * values). */
    private final Environment environment;

    public SettingsService(SettingsStorePort store, Environment environment) {
        this.store = store;
        this.environment = environment;
    }

    /** Whether the key may be edited through this use case. */
    public boolean isEditable(String key) {
        return key != null && EDITABLE_KEYS.contains(key.trim());
    }

    /**
     * Validates the candidate value for the key WITHOUT saving — the
     * driving adapter uses it to refuse a typo before anything is
     * written. Empty answer: the empty result means "clear the
     * override" (allowed for every key).
     *
     * @return empty when the value is valid; otherwise the refusal
     *         reason
     */
    public Optional<String> validate(String key, String value) {
        if (!isEditable(key)) {
            return Optional.of("Unknown or non-editable setting '" + key
                    + "' — editable: " + String.join(", ", EDITABLE_KEYS));
        }
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.isEmpty()) {
            return Optional.empty(); // clearing the override is always valid
        }
        switch (key) {
            case SCRAPE_CONNECTOR -> {
                if (!Set.of("static", "live").contains(trimmed.toLowerCase())) {
                    return Optional.of("'" + value + "' is not a scrape connector mode "
                            + "(static or live)");
                }
            }
            case EXTRACTION_MAX_ITEMS -> {
                Integer parsed = parsePositiveInt(trimmed);
                if (parsed == null) {
                    return Optional.of("'" + value + "' is not a positive integer "
                            + "(the extraction cap, 1..10000)");
                }
                if (parsed > 10000) {
                    return Optional.of("The extraction cap must be <= 10000, got " + parsed);
                }
            }
            case VERIFICATION_TIMEOUT -> {
                Integer parsed = parsePositiveInt(trimmed);
                if (parsed == null) {
                    return Optional.of("'" + value + "' is not a positive integer "
                            + "(the verification timeout, seconds)");
                }
                if (parsed > 3600) {
                    return Optional.of("The verification timeout must be <= 3600 s, got "
                            + parsed);
                }
            }
            default -> {
                return Optional.of("Unknown or non-editable setting '" + key + "'");
            }
        }
        return Optional.empty();
    }

    /**
     * Validates and persists the override. The caller applies the value
     * to the live consumers afterwards (the infrastructure applier also
     * applies every stored override at boot, so the persistence path
     * alone is sufficient across restarts).
     *
     * @throws IllegalArgumentException for a non-editable key or an
     *                                  invalid value (validated before
     *                                  saving — nothing partial is
     *                                  written)
     */
    public void set(String key, String value) {
        String refusal = validate(key, value).orElse(null);
        if (refusal != null) {
            throw new IllegalArgumentException(refusal);
        }
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.isEmpty()) {
            store.delete(key);
            log.info("CONFIG override cleared: {} (the application.yml value applies again)",
                    key);
            return;
        }
        store.save(key, normalized(key, trimmed));
        log.info("CONFIG override set: {} = {} (stored; applied over application.yml)", key, trimmed);
    }

    /** The stored override of one key, or empty. */
    public Optional<String> overrideOf(String key) {
        return store.find(key);
    }

    /** Every stored override, key -> value (stable key order). */
    public Map<String, String> overrides() {
        return new LinkedHashMap<>(store.all());
    }

    /** The effective value of one key: the stored override, else the
     * LIVE Environment (system properties layered over application.yml
     * — US-16.7: reading system properties alone missed every value
     * configured only in the YAML and displayed it as unset although
     * it IS configured and effective). */
    public String effective(String key) {
        return store.find(key)
                .orElseGet(() -> {
                    String fromEnv = environment.getProperty(key);
                    return fromEnv == null ? "" : fromEnv;
                });
    }

    private static String normalized(String key, String value) {
        // The stored form matches the consumers' accepted form.
        return switch (key) {
            case SCRAPE_CONNECTOR -> value.toLowerCase();
            default -> value;
        };
    }

    private static Integer parsePositiveInt(String raw) {
        try {
            int parsed = Integer.parseInt(raw);
            return parsed >= 1 ? parsed : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
