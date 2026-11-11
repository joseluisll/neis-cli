package es.jllopez.neis.domain.model;

import java.time.Instant;

/**
 * One execution record (US-05.3, issue #26): every task run — manual
 * or scheduled — leaves one, with status, timestamps and outcome, for
 * traceability. Immutable snapshot; the executor replaces it through
 * the store as the run progresses.
 *
 * <p>{@code chainId} links a record to the chain definition it ran
 * (US-08, epic #8); null for single-task runs. A chain run's
 * {@code taskCode} is CHAIN, the runnable kind the record shows.</p>
 */
public record TaskExecution(String id, String taskCode, String addressId, String chainId,
                            TriggerType triggerType, ExecutionStatus status,
                            Instant startedAt, Instant finishedAt, String outcome) {

    public TaskExecution {
        if (status == null) {
            status = ExecutionStatus.PENDING;
        }
    }

    public TaskExecution with(ExecutionStatus newStatus, Instant finished, String result) {
        return new TaskExecution(id, taskCode, addressId, chainId, triggerType,
                newStatus, startedAt, finished, result);
    }
}
