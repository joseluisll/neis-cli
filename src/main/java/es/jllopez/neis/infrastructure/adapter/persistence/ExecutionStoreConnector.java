package es.jllopez.neis.infrastructure.adapter.persistence;

import es.jllopez.neis.domain.model.TaskExecution;
import es.jllopez.neis.domain.port.ExecutionStorePort;
import es.jllopez.neis.infrastructure.persistence.ExecutionEntity;
import es.jllopez.neis.infrastructure.persistence.ExecutionJpaRepository;
import es.jllopez.neis.infrastructure.persistence.TaskTypeJpaRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
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

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<Map<String, String>> PARAMS = new TypeReference<>() {};

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
        entity.setParameters(toJson(execution.parameters()));
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
                e.getOutcome(),
                fromJson(e.getParameters()));
    }

    /** Parameters to the V12 JSON column; null for an empty map (the
     * column stays NULL, matching the pre-V12 rows). */
    private static String toJson(Map<String, String> parameters) {
        if (parameters == null || parameters.isEmpty()) {
            return null;
        }
        try {
            return JSON.writeValueAsString(parameters);
        } catch (com.fasterxml.jackson.core.JacksonException e) {
            // Parameters are plain string->string values; serialization
            // cannot fail in practice, and a lost detail column must
            // never fail the lifecycle save (the run itself continues).
            return null;
        }
    }

    /** The V12 JSON column back to the parameter map; an unreadable
     * value degrades to an empty map, never a failed read. */
    private static Map<String, String> fromJson(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return JSON.readValue(json, PARAMS);
        } catch (com.fasterxml.jackson.core.JacksonException e) {
            return Map.of();
        }
    }
}
