package es.jllopez.neis.domain.model;

/**
 * Lifecycle of a remote job (US-18.2, issue #117): a long-running
 * operation submitted over the remote adapters (REST API, US-18.1)
 * that returns a job id immediately and is polled for status/result.
 *
 * <p>QUEUED -> RUNNING -> COMPLETED | FAILED, plus CANCELLED for the
 * deferred cancellation follow-up (US-18.7, issue #122 — reserved
 * now so the persistence mapping never needs a re-migration).</p>
 */
public enum JobStatus {
    QUEUED, RUNNING, COMPLETED, FAILED, CANCELLED;

    /** US-18.7 (issue #122): a terminal job never changes again. */
    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == CANCELLED;
    }
}
