package es.jllopez.neis.application;

import es.jllopez.neis.domain.model.ExecutionStatus;
import es.jllopez.neis.domain.model.JobStatus;
import es.jllopez.neis.domain.model.RemoteJob;
import es.jllopez.neis.domain.model.TaskExecution;
import es.jllopez.neis.domain.model.TriggerType;
import es.jllopez.neis.domain.port.JobStorePort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Use case: the asynchronous job model behind the remote adapters
 * (US-18.2, issue #117). A remote invocation of a long-running
 * operation returns a job id immediately; the job WRAPS the execution
 * layer — the run itself still goes through {@link ExecutionService}
 * with {@link TriggerType#REMOTE}, so per-address serialization,
 * the execution record and its provenance are untouched.
 *
 * <p>The job's own lifecycle (QUEUED -> RUNNING -> terminal) is
 * persisted through {@link JobStorePort}; a FAILED job always carries
 * the failure reason in its {@code error} field, whether the failure
 * happened at submission (unknown task type, ineligible address) or
 * during the run. There is no job-level timeout: executions are
 * bounded by the connector timeouts and always reach a terminal
 * execution status, which this service waits out.</p>
 */
@Service
public class RemoteJobService {

    private static final Logger log = LoggerFactory.getLogger(RemoteJobService.class);

    /** The remote-jobs result column is 4096 chars; stay under it. */
    private static final int RESULT_LIMIT = 4000;

    private final JobStorePort jobs;
    private final ExecutionService executions;
    private final Executor jobRunner;

    public RemoteJobService(JobStorePort jobs, ExecutionService executions,
                            @Qualifier("jobRunner") Executor jobRunner) {
        this.jobs = jobs;
        this.executions = executions;
        this.jobRunner = jobRunner;
    }

    /**
     * Submits a single-task run as a remote job and returns the QUEUED
     * job immediately.
     *
     * @throws IllegalArgumentException mirrors {@link ExecutionService}
     *         — the refusal is ALSO recorded on the job (FAILED), so a
     *         caller that only polls sees the reason.
     */
    public RemoteJob submitRunTask(String taskCode, String addressId,
                                   Map<String, String> parameters) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("task-code", taskCode);
        if (parameters != null) {
            params.putAll(parameters);
        }
        return submit("run-task", addressId, params,
                () -> executions.submit(taskCode, addressId, TriggerType.REMOTE,
                        parameters));
    }

    /**
     * Submits a chain run as a remote job; same contract as
     * {@link #submitRunTask}.
     */
    public RemoteJob submitRunChain(String chainId, String addressId) {
        return submit("run-chain", addressId, Map.of("chain-id", chainId),
                () -> executions.submitChain(chainId, addressId, TriggerType.REMOTE));
    }

    /** One job record, if it exists. */
    public Optional<RemoteJob> job(String id) {
        return jobs.findById(id);
    }

    /** All job records, newest first, optionally filtered by status. */
    public List<RemoteJob> jobs(JobStatus status) {
        // Java-side filter BEFORE any caller-side limit: the SQL layer
        // has no status-ranked query, and a limit would let excluded
        // rows eat ranked slots (same shape as the news-list batching).
        return jobs.findAll().stream()
                .filter(job -> status == null || job.status() == status)
                .toList();
    }

    private RemoteJob submit(String operation, String addressId,
                             Map<String, String> parameters,
                             Supplier<TaskExecution> run) {
        RemoteJob job = new RemoteJob("JOB-" + UUID.randomUUID(), operation,
                addressId, parameters, JobStatus.QUEUED, Instant.now(),
                null, null, null, null, null);
        jobs.save(job);
        log.info("[JOB] {} submitted: {} (address {})", job.id(), operation, addressId);
        CompletableFuture.runAsync(() -> runJob(job.id(), run), jobRunner)
                .whenComplete((v, e) -> {
                    if (e != null) {
                        log.error("[JOB] {} runner failed: {}", job.id(), e.toString());
                    }
                });
        return job;
    }

    private void runJob(String jobId, Supplier<TaskExecution> run) {
        RemoteJob job = jobs.findById(jobId).orElse(null);
        if (job == null) {
            log.error("[JOB] {} vanished before its runner started", jobId);
            return;
        }
        job = save(job.transition(JobStatus.RUNNING, Instant.now(), null));
        try {
            TaskExecution execution = run.get();
            job = save(job.withExecution(execution.id()));
            TaskExecution finalRecord = awaitTerminal(execution.id());
            if (finalRecord == null) {
                save(job.finish(JobStatus.FAILED, Instant.now(), null,
                        "Execution record vanished: " + execution.id()));
                log.error("[JOB] {} FAILED: execution record vanished", jobId);
            } else if (finalRecord.status() == ExecutionStatus.SUCCESS) {
                save(job.finish(JobStatus.COMPLETED, Instant.now(),
                        finalRecord.outcome(), null));
                log.info("[JOB] {} COMPLETED (execution {})", jobId, execution.id());
            } else {
                save(job.finish(JobStatus.FAILED, Instant.now(), null,
                        finalRecord.outcome()));
                log.info("[JOB] {} FAILED (execution {}): {}", jobId,
                        execution.id(), finalRecord.outcome());
            }
        } catch (Throwable t) {
            String error = t.getClass().getSimpleName()
                    + (t.getMessage() != null ? ": " + t.getMessage() : "");
            save(job.finish(JobStatus.FAILED, Instant.now(), null, truncate(error)));
            log.info("[JOB] {} FAILED before/at run: {}", jobId, error);
            if (t instanceof Error errorT) {
                // Re-throw Errors after recording: the JVM state is not
                // trustworthy and must not be swallowed (same rule as
                // ExecutionService.run).
                throw errorT;
            }
        }
    }

    /**
     * Polls the execution record until it reaches a terminal status.
     * No timeout: executions always terminate (the work is bounded by
     * the connector timeouts), and a job must not fail spuriously just
     * because a run is slow.
     */
    private TaskExecution awaitTerminal(String executionId) {
        while (true) {
            TaskExecution record;
            try {
                record = executions.show(executionId);
            } catch (IllegalArgumentException vanished) {
                return null; // the record is gone; runJob records the failure
            }
            if (record == null) {
                // Defensive: a read that answers "absent" must never be
                // waited out — treat it as vanished (found live: an
                // unstubbed test double made this loop forever while
                // the throwing production path was already handled).
                return null;
            }
            if (record.status() == ExecutionStatus.SUCCESS
                    || record.status() == ExecutionStatus.FAILED) {
                return record;
            }
            try {
                TimeUnit.MILLISECONDS.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return record;
            }
        }
    }

    private RemoteJob save(RemoteJob job) {
        try {
            return jobs.save(job);
        } catch (RuntimeException e) {
            // Every store failure is caught and logged: the lifecycle
            // must never strand the job (same rule as the execution
            // record's trySave).
            log.error("[JOB] {}: could not record the {} transition: {}",
                    job.id(), job.status(), e.toString());
            return job;
        }
    }

    private String truncate(String value) {
        return value.length() <= RESULT_LIMIT
                ? value : value.substring(0, RESULT_LIMIT) + "…";
    }
}
