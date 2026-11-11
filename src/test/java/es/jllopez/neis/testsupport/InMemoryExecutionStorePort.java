package es.jllopez.neis.testsupport;

import es.jllopez.neis.domain.model.TaskExecution;
import es.jllopez.neis.domain.port.ExecutionStorePort;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Test double of {@link ExecutionStorePort}: an in-memory map, newest
 * first for findAll. Stands in for the SQLite-backed connector in
 * executor unit tests.
 */
public class InMemoryExecutionStorePort implements ExecutionStorePort {

    private final Map<String, TaskExecution> executions = new LinkedHashMap<>();
    private final List<String> taskTypes;

    public InMemoryExecutionStorePort(List<String> taskTypes) {
        this.taskTypes = new ArrayList<>(taskTypes);
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
        return executions.values().stream().toList().reversed();
    }

    @Override
    public List<String> taskTypes() {
        return List.copyOf(taskTypes);
    }
}
