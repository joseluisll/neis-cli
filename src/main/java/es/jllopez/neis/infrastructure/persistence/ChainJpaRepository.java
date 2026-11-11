package es.jllopez.neis.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/** Spring Data repository for the {@code chains} table (US-08.1). */
public interface ChainJpaRepository extends JpaRepository<ChainEntity, String> {

    Optional<ChainEntity> findByName(String name);
}
