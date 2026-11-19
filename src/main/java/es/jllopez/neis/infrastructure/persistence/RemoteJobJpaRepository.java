package es.jllopez.neis.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * Spring Data repository for remote jobs (US-18.2, issue #117). Part
 * of the driven adapter behind {@code JobStorePort}.
 */
public interface RemoteJobJpaRepository extends JpaRepository<RemoteJobEntity, String> {

    List<RemoteJobEntity> findAllByOrderBySubmittedAtDesc();
}
