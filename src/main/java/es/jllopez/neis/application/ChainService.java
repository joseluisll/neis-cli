package es.jllopez.neis.application;

import es.jllopez.neis.domain.model.Chain;
import es.jllopez.neis.domain.model.ChainTask;
import es.jllopez.neis.domain.port.ChainStorePort;
import es.jllopez.neis.domain.port.ExecutionStorePort;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Use case: manage chain definitions (US-08.1, issue #33) — create,
 * list and inspect the named ordered task lists the executor runs
 * (US-08.2). Creation validates the definition against the
 * requirements: a name, one to {@value Chain#MAX_TASKS} tasks, every
 * task type known to the executor's vocabulary, no nested chains.
 */
@Service
public class ChainService {

    private final ChainStorePort store;
    /** The task-type vocabulary the steps must come from. */
    private final ExecutionStorePort executionStore;

    public ChainService(ChainStorePort store, ExecutionStorePort executionStore) {
        this.store = store;
        this.executionStore = executionStore;
    }

    /**
     * Creates and stores a chain. Steps are position-normalized in the
     * given order (1..n), so callers pass an ordered list, not
     * positions.
     *
     * @throws IllegalArgumentException for a blank name, an empty step
     *                                  list, more than
     *                                  {@value Chain#MAX_TASKS} steps, an
     *                                  unknown task type or a nested
     *                                  CHAIN step
     * @throws IllegalStateException    for a name already in use
     */
    public Chain create(String name, String description, List<ChainTask> steps) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("A chain needs a name");
        }
        String trimmed = name.trim();
        store.findByName(trimmed).ifPresent(existing -> {
            throw new IllegalStateException("A chain named '" + trimmed
                    + "' already exists: " + existing.id());
        });
        if (steps == null || steps.isEmpty()) {
            throw new IllegalArgumentException("A chain needs at least one task");
        }
        List<String> known = executionStore.taskTypes();
        List<ChainTask> normalized = new ArrayList<>();
        int position = 1;
        for (ChainTask step : steps) {
            if ("CHAIN".equals(step.taskCode())) {
                throw new IllegalArgumentException(
                        "A chain step cannot be CHAIN itself — nested chains are "
                                + "not supported in this iteration");
            }
            if (!known.contains(step.taskCode())) {
                throw new IllegalArgumentException("Unknown task type: "
                        + step.taskCode() + " (known: " + known + ")");
            }
            normalized.add(new ChainTask(position++, step.taskCode(), step.parameters()));
        }
        Chain chain = new Chain("CHAIN-" + UUID.randomUUID(), trimmed, description,
                Instant.now(), normalized);
        return store.save(chain);
    }

    /** Every chain definition, ordered by name. */
    public List<Chain> list() {
        return store.findAll();
    }

    /** One chain definition.
     *
     * @throws IllegalArgumentException for an unknown id */
    public Chain show(String id) {
        return store.findById(id).orElseThrow(
                () -> new IllegalArgumentException("Unknown chain: " + id));
    }
}
