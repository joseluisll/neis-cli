package es.jllopez.neis.infrastructure.adapter.persistence;

import es.jllopez.neis.domain.port.SettingsStorePort;
import es.jllopez.neis.infrastructure.persistence.SettingEntity;
import es.jllopez.neis.infrastructure.persistence.SettingsJpaRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Driven adapter implementing {@link SettingsStorePort} over the Spring
 * Data repository (V13, US-16.5 review finding #97): persisted
 * configuration overrides in the SQLite {@code settings} table, applied
 * over the externalized configuration (application.yml stays the
 * source of record — the override wins).
 */
@Component
public class SettingsStoreConnector implements SettingsStorePort {

    private final SettingsJpaRepository settings;

    public SettingsStoreConnector(SettingsJpaRepository settings) {
        this.settings = settings;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, String> all() {
        Map<String, String> out = new LinkedHashMap<>();
        // Natural order by key: the overrides view is stable.
        settings.findAll().stream()
                .sorted((a, b) -> a.getSettingKey().compareTo(b.getSettingKey()))
                .forEach(e -> out.put(e.getSettingKey(), e.getSettingValue()));
        return out;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<String> find(String key) {
        return settings.findById(key).map(SettingEntity::getSettingValue);
    }

    @Override
    @Transactional
    public void save(String key, String value) {
        SettingEntity entity = settings.findById(key).orElseGet(SettingEntity::new);
        entity.setSettingKey(key);
        entity.setSettingValue(value);
        entity.setUpdatedAt(Instant.now().toString());
        settings.save(entity);
    }

    @Override
    @Transactional
    public void delete(String key) {
        settings.findById(key).ifPresent(settings::delete);
    }
}
