package es.jllopez.neis.domain.model;

import java.time.Instant;
import java.util.Map;

/**
 * One remote job (US-18.2, issue #117): the asynchronous envelope the
 * remote adapters hand back to their callers. The job WRAPS the
 * execution layer — it never duplicates it: when the wrapped operation
 * is a task/chain run, {@code executionId} links the execution record
 * (US-05.3) the job produced, and that run carries
 * {@link TriggerType#REMOTE} so remote-triggered work stays
 * distinguishable from MANUAL and SCHEDULED runs.
 *
 * <p>Immutable snapshot; the job runner replaces it through the store
 * as the lifecycle progresses (QUEUED -> RUNNING -> terminal). The
 * {@code result} carries the wrapped operation's outcome payload
 * (for a run: the execution's outcome summary); {@code error} carries
 * the failure reason for FAILED jobs — observable remotely, not only
 * in the local log.</p>
 */
public record RemoteJob(String id, String operation, String addressId,
                        Map<String, String> parameters, JobStatus status,
                        Instant submittedAt, Instant startedAt, Instant finishedAt,
                        String executionId, String result, String error) {

    public RemoteJob {
        parameters = parameters == null ? Map.of() : Map.copyOf(parameters);
        if (status == null) {
            status = JobStatus.QUEUED;
        }
    }

    /** Moves the job to a new status, filling in the timestamps given. */
    public RemoteJob transition(JobStatus newStatus, Instant started, Instant finished) {
        return new RemoteJob(id, operation, addressId, parameters, newStatus,
                submittedAt, started != null ? started : startedAt,
                finished != null ? finished : finishedAt,
                executionId, result, error);
    }

    /** Links the execution record this job produced (run operations). */
    public RemoteJob withExecution(String executionId) {
        return new RemoteJob(id, operation, addressId, parameters, status,
                submittedAt, startedAt, finishedAt, executionId, result, error);
    }

    /** Terminal transition: carries the result and/or the failure reason. */
    public RemoteJob finish(JobStatus finalStatus, Instant finished,
                            String result, String error) {
        return new RemoteJob(id, operation, addressId, parameters, finalStatus,
                submittedAt, startedAt, finished, executionId, result, error);
    }

    public boolean isTerminal() {
        return status == JobStatus.COMPLETED || status == JobStatus.FAILED
                || status == JobStatus.CANCELLED;
    }
}
