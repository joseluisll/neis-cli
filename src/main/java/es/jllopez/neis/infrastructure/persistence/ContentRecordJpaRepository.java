package es.jllopez.neis.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** Spring Data repository for the {@code content_records} table (US-11.2). */
public interface ContentRecordJpaRepository extends JpaRepository<ContentRecordEntity, String> {

    List<ContentRecordEntity> findByAddressIdOrderByCreatedAtDesc(String addressId);

    /** The records of one execution (US-18.4 review: the MCP content
     * tool routes through use-case queries, not an unbounded listAll). */
    List<ContentRecordEntity> findByExecutionIdOrderByCreatedAtDesc(
            String executionId);
}
