package es.jllopez.neis.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * Spring Data repository for remote jobs (US-18.2, issue #117). Part
 * of the driven adapter behind {@code JobStorePort}.
 */
public interface RemoteJobJpaRepository extends JpaRepository<RemoteJobEntity, String> {

    List<RemoteJobEntity> findAllByOrderBySubmittedAtDesc();

    /**
     * The CAS of JobStorePort.saveIfStatus (US-18.7): an ATOMIC
     * conditional update — applies only when the stored status still
     * matches {@code expected}, returning the affected-row count as
     * 0/1. Native SQL (JPQL cannot express the conditional WHERE
     * reliably with record-shaped params; the connector computes the
     * ISO-TEXT timestamp strings, matching the column conventions).
     */
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query(value = """
            update remote_jobs
               set status = :status,
                   started_at = :startedAt,
                   finished_at = :finishedAt,
                   execution_id = :executionId,
                   result = :result,
                   error = :error
             where id = :id and status = :expected
            """, nativeQuery = true)
    int casUpdate(@org.springframework.data.repository.query.Param("id") String id,
                  @org.springframework.data.repository.query.Param("expected") String expected,
                  @org.springframework.data.repository.query.Param("status") String status,
                  @org.springframework.data.repository.query.Param("startedAt") String startedAt,
                  @org.springframework.data.repository.query.Param("finishedAt") String finishedAt,
                  @org.springframework.data.repository.query.Param("executionId") String executionId,
                  @org.springframework.data.repository.query.Param("result") String result,
                  @org.springframework.data.repository.query.Param("error") String error);
}
