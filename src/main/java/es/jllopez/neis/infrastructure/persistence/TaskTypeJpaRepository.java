package es.jllopez.neis.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data repository for the task-type vocabulary (US-05). The
 * {@code executions.task_code} FK targets this table, so the fixed
 * vocabulary must exist before any execution record is written.
 */
public interface TaskTypeJpaRepository extends JpaRepository<TaskTypeEntity, String> {
}
