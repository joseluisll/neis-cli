package es.jllopez.neis.domain.model;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;

/**
 * A chain of tasks (US-08.1, issue #33): an ordered list of task
 * elements, at most {@link #MAX_TASKS} in this iteration, executed
 * sequentially (US-08.2, issue #34) by the executor over an address.
 * A chain is NOT bound to an address — the execution is, so one chain
 * can run over many addresses.
 *
 * <p>DAG generalization: elements carry their own position, so the
 * sequence is data, not structure; a later evolution to a DAG adds
 * dependency edges between elements without touching this shape.</p>
 *
 * <p>Immutable snapshot; tasks are kept in position order.</p>
 */
public record Chain(String id, String name, String description, Instant createdAt,
                    List<ChainTask> tasks) {

    /** Maximum elements per chain in this iteration (US-08.1). */
    public static final int MAX_TASKS = 10;

    public Chain {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("A chain needs a name");
        }
        if (tasks == null || tasks.isEmpty()) {
            throw new IllegalArgumentException(
                    "A chain needs at least one task (ordered list, max "
                            + MAX_TASKS + " elements)");
        }
        if (tasks.size() > MAX_TASKS) {
            throw new IllegalArgumentException(
                    "A chain carries at most " + MAX_TASKS + " tasks, got: "
                            + tasks.size());
        }
        List<ChainTask> ordered = List.copyOf(tasks).stream()
                .sorted(Comparator.comparingInt(ChainTask::position))
                .toList();
        for (int i = 0; i < ordered.size(); i++) {
            if (ordered.get(i).position() != i + 1) {
                throw new IllegalArgumentException(
                        "Chain task positions must be 1..n without gaps, got: "
                                + ordered.stream().map(ChainTask::position).toList());
            }
        }
        tasks = ordered;
    }

    /** The tasks in execution order (position 1..n). */
    @Override
    public List<ChainTask> tasks() {
        return tasks; // already ordered by the compact constructor
    }
}
