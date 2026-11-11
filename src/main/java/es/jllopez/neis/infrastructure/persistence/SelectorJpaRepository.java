package es.jllopez.neis.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data repository for stored working selectors (US-02.1). Part of
 * the driven adapter behind {@code SelectorStorePort}.
 */
public interface SelectorJpaRepository extends JpaRepository<SelectorEntity, String> {
}
