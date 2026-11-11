package es.jllopez.neis.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * Spring Data repository for execution records (US-05.3). Part of the
 * driven adapter behind {@code ExecutionStorePort}.
 */
public interface ExecutionJpaRepository extends JpaRepository<ExecutionEntity, String> {

    List<ExecutionEntity> findAllByOrderByStartedAtDesc();
}
