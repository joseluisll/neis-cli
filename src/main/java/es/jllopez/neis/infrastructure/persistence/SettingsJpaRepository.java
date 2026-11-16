package es.jllopez.neis.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data repository for persisted configuration overrides (V13).
 * Part of the driven adapter behind {@code SettingsStorePort}.
 */
public interface SettingsJpaRepository extends JpaRepository<SettingEntity, String> {
}
