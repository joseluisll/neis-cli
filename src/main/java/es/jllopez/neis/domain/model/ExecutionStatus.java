package es.jllopez.neis.domain.model;

/**
 * The lifecycle status of a task run (US-05.3, issue #26). Stored in
 * the {@code executions.status} column of schema V1 (CHECK constraint
 * lists these exact values).
 */
public enum ExecutionStatus {
    /** Created, waiting to run. */
    PENDING,
    /** The executor is running the task right now. */
    RUNNING,
    /** Finished successfully. */
    SUCCESS,
    /** Finished with a failure; the outcome carries the reason. */
    FAILED
}
