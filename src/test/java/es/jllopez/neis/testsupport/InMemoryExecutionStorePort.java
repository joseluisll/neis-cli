package es.jllopez.neis.testsupport;

import es.jllopez.neis.domain.model.TaskExecution;
import es.jllopez.neis.domain.port.ExecutionStorePort;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Test double of {@link ExecutionStorePort}: a concurrent map — worker
 * threads save while the caller thread polls in await(), so the plain
 * LinkedHashMap of the first draft was a JMM data race (slice-3 review
 * finding). Ordering follows the port contract: newest first, by
 * started date, nulls last (the SQLite connector orders its query the
 * same way).
 */
public class InMemoryExecutionStorePort implements ExecutionStorePort {

    private final Map<String, TaskExecution> executions = new ConcurrentHashMap<>();
    private final List<String> taskTypes;

    public InMemoryExecutionStorePort(List<String> taskTypes) {
        this.taskTypes = List.copyOf(taskTypes);
    }

    @Override
    public TaskExecution save(TaskExecution execution) {
        executions.put(execution.id(), execution);
        return execution;
    }

    @Override
    public Optional<TaskExecution> findById(String id) {
        return Optional.ofNullable(executions.get(id));
    }

    @Override
    public List<TaskExecution> findAll() {
        return executions.values().stream()
                .sorted(Comparator.comparing(TaskExecution::startedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
    }

    @Override
    public List<String> taskTypes() {
        return taskTypes;
    }
}
