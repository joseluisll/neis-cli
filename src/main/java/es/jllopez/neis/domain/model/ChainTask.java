package es.jllopez.neis.domain.model;

import java.util.Map;

/**
 * One element of a chain (US-08.1, issue #33): a task type plus the
 * per-task parameters (US-08.3, issue #35) it runs with. The explicit
 * position (1-based, unique within its chain) — not the list index —
 * orders the execution, which is what lets the model generalize to a
 * DAG later: a DAG generalizes "position" into dependency edges
 * without changing the element itself.
 *
 * <p>Immutable value; parameters default to an empty map.</p>
 */
public record ChainTask(int position, String taskCode, Map<String, String> parameters) {

    public ChainTask {
        if (position < 1 || position > Chain.MAX_TASKS) {
            throw new IllegalArgumentException(
                    "Chain step position must be between 1 and " + Chain.MAX_TASKS
                            + ", got: " + position);
        }
        if (taskCode == null || taskCode.isBlank()) {
            throw new IllegalArgumentException("A chain step needs a task type code");
        }
        parameters = parameters == null ? Map.of() : Map.copyOf(parameters);
    }

    /** A parameterless step at the given position. */
    public static ChainTask at(int position, String taskCode) {
        return new ChainTask(position, taskCode, Map.of());
    }
}
