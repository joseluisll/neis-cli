package es.jllopez.neis.domain.port;

import es.jllopez.neis.domain.model.JobStatus;
import es.jllopez.neis.domain.model.RemoteJob;

import java.util.List;
import java.util.Optional;

/**
 * Store port for remote jobs (US-18.2, issue #117): every long-running
 * remote invocation leaves a persisted, queryable record. The job
 * runner drives the lifecycle (QUEUED -> RUNNING -> terminal) through
 * this port; the remote adapters read it to answer status polling.
 */
public interface JobStorePort {

    RemoteJob save(RemoteJob job);

    /**
     * Conditional save (US-18.7, issue #122): the record applies ONLY
     * if the stored status still equals {@code expectedStatus} — the
     * compare-and-set that closes the cancel-vs-runner race windows
     * (a plain save is last-writer-wins and can overwrite a CANCELLED
     * record). Returns false when the stored status moved on; the
     * caller must then RE-READ and decide against the fresh state.
     */
    boolean saveIfStatus(String jobId, JobStatus expectedStatus,
                         RemoteJob record);

    Optional<RemoteJob> findById(String id);

    /** Every job record, newest first (by submission time). */
    List<RemoteJob> findAll();
}
