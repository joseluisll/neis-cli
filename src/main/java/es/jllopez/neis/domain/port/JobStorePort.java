package es.jllopez.neis.domain.port;

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

    Optional<RemoteJob> findById(String id);

    /** Every job record, newest first (by submission time). */
    List<RemoteJob> findAll();
}
