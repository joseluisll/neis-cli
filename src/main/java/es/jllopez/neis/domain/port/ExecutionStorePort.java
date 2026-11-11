package es.jllopez.neis.domain.port;

import es.jllopez.neis.domain.model.TaskExecution;

import java.util.List;
import java.util.Optional;

/**
 * Store port for execution records (US-05.3, issue #26): every task
 * run leaves a traceable record. The executor drives the lifecycle
 * (PENDING -> RUNNING -> SUCCESS/FAILED) through this port.
 */
public interface ExecutionStorePort {

    TaskExecution save(TaskExecution execution);

    Optional<TaskExecution> findById(String id);

    /** Every execution record, newest first. */
    List<TaskExecution> findAll();

    /** The known task-type vocabulary (the {@code task_types} table). */
    List<String> taskTypes();
}
