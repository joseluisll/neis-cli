package es.jllopez.neis.domain.model;

import java.time.Instant;

/**
 * One execution record (US-05.3, issue #26): every task run — manual
 * or scheduled — leaves one, with status, timestamps and outcome, for
 * traceability. Immutable snapshot; the executor replaces it through
 * the store as the run progresses.
 */
public record TaskExecution(String id, String taskCode, String addressId,
                            TriggerType triggerType, ExecutionStatus status,
                            Instant startedAt, Instant finishedAt, String outcome) {

    public TaskExecution {
        if (status == null) {
            status = ExecutionStatus.PENDING;
        }
    }

    public TaskExecution with(ExecutionStatus newStatus, Instant finished, String result) {
        return new TaskExecution(id, taskCode, addressId, triggerType, newStatus,
                startedAt, finished, result);
    }
}
