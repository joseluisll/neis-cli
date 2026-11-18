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

    /**
     * Replaces a chain's definition (US-10.1, issue #39): name,
     * description and the ordered task list. The position normalization
     * and validation rules are the same as {@link #create} — an edit is
     * a re-save with the chain's existing id and creation instant.
     *
     * @throws IllegalArgumentException for an unknown id, an empty/blank
     *                                  name, no steps, more than
     *                                  {@value Chain#MAX_TASKS} steps, an
     *                                  unknown task type or a nested
     *                                  CHAIN step
     * @throws IllegalStateException    when renaming onto a name another
     *                                  chain already uses
     */
    public Chain update(String id, String name, String description,
                        List<ChainTask> steps) {
        Chain existing = show(id);
        // US-10.1 review B2: null/blank KEEP the stored value on every
        // field — the TUI prompt and the CLI help both promise "empty
        // = keep"; a verbatim null description silently WIPED the
        // stored one (data loss contradicting the contract). (Clearing
        // a description is `chain update --description " "`? No — a
        // deliberate clear is an edit that sets a new text; keeping is
        // the empty-keeps rule everywhere.)
        String trimmed = name == null || name.isBlank() ? null : name.trim();
        if (trimmed != null && !trimmed.equals(existing.name())) {
            store.findByName(trimmed).ifPresent(other -> {
                if (!other.id().equals(id)) {
                    throw new IllegalStateException("A chain named '" + trimmed
                            + "' already exists: " + other.id());
                }
            });
        }
        String keptDescription = description == null || description.isBlank()
                ? existing.description() : description.trim();
        List<ChainTask> normalized = validateSteps(steps);
        Chain updated = new Chain(existing.id(),
                trimmed == null ? existing.name() : trimmed,
                keptDescription, existing.createdAt(), normalized);
        return store.save(updated);
    }

    /**
     * Removes a chain definition (US-10.1, issue #39). Executions that
     * already ran keep their records — the execution history is the
     * run log, not a foreign key into the definition.
     *
     * @throws IllegalArgumentException for an unknown id
     */
    public void delete(String id) {
        show(id);
        store.deleteById(id);
    }

    /** The create/update task-list validation, shared: 1..MAX steps,
     * no nested CHAIN, every type a registered task type; positions
     * renumbered 1..n in the given order (the list IS the order). */
    private List<ChainTask> validateSteps(List<ChainTask> steps) {
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
        return normalized;
    }
}
