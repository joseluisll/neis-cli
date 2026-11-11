package es.jllopez.neis.infrastructure.adapter.persistence;

import es.jllopez.neis.domain.model.TaskExecution;
import es.jllopez.neis.domain.port.ExecutionStorePort;
import es.jllopez.neis.infrastructure.persistence.ExecutionEntity;
import es.jllopez.neis.infrastructure.persistence.ExecutionJpaRepository;
import es.jllopez.neis.infrastructure.persistence.TaskTypeJpaRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.time.Instant;
import java.util.Optional;

/**
 * Driven adapter implementing {@link ExecutionStorePort} over the
 * Spring Data JPA repositories (US-05.3, issue #26): execution records
 * in the SQLite {@code executions} table of schema V1, with the
 * task-type vocabulary of the {@code task_types} table.
 */
@Component
public class ExecutionStoreConnector implements ExecutionStorePort {

    private final ExecutionJpaRepository executions;
    private final TaskTypeJpaRepository taskTypes;

    public ExecutionStoreConnector(ExecutionJpaRepository executions,
                                   TaskTypeJpaRepository taskTypes) {
        this.executions = executions;
        this.taskTypes = taskTypes;
    }

    @Override
    @Transactional
    public TaskExecution save(TaskExecution execution) {
        ExecutionEntity entity = executions.findById(execution.id())
                .orElseGet(ExecutionEntity::new);
        entity.setId(execution.id());
        entity.setChainId(execution.chainId());
        entity.setTaskCode(execution.taskCode());
        entity.setAddressId(execution.addressId());
        entity.setTriggerType(execution.triggerType());
        entity.setStatus(execution.status());
        entity.setStartedAt(execution.startedAt() == null
                ? null : execution.startedAt().toString());
        entity.setFinishedAt(execution.finishedAt() == null
                ? null : execution.finishedAt().toString());
        entity.setOutcome(execution.outcome());
        return toDomain(executions.save(entity));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<TaskExecution> findById(String id) {
        return executions.findById(id).map(this::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<TaskExecution> findAll() {
        return executions.findAllByOrderByStartedAtDesc().stream()
                .map(this::toDomain).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<String> taskTypes() {
        return taskTypes.findAll().stream().map(t -> t.getCode()).sorted().toList();
    }

    private TaskExecution toDomain(ExecutionEntity e) {
        return new TaskExecution(e.getId(), e.getTaskCode(), e.getAddressId(),
                e.getChainId(),
                e.getTriggerType(), e.getStatus(),
                e.getStartedAt() == null ? null : Instant.parse(e.getStartedAt()),
                e.getFinishedAt() == null ? null : Instant.parse(e.getFinishedAt()),
                e.getOutcome());
    }
}
