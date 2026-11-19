package es.jllopez.neis.application;

import es.jllopez.neis.domain.model.ExecutionStatus;
import es.jllopez.neis.domain.model.JobStatus;
import es.jllopez.neis.domain.model.RemoteJob;
import es.jllopez.neis.domain.model.TaskExecution;
import es.jllopez.neis.domain.model.TriggerType;
import es.jllopez.neis.testsupport.InMemoryExecutionStorePort;
import es.jllopez.neis.testsupport.InMemoryJobStorePort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * US-18.10 (issue #133): jobs and executions orphaned by a previous
 * process are finalized on startup, CAS-guarded, and records of THIS
 * boot are never touched.
 */
class JobStartupReconciliationTest {

    private static final Instant PAST = Instant.parse("2026-11-19T10:00:00Z");

    private InMemoryJobStorePort jobs;
    private InMemoryExecutionStorePort executions;
    private JobStartupReconciliation reconciler;

    @BeforeEach
    void setUp() {
        jobs = new InMemoryJobStorePort();
        executions = new InMemoryExecutionStorePort(List.of("WEBSCRAPE"));
        // the boot instant is captured at construction: records stamped
        // AFTER this point simulate runs of the current process
        reconciler = new JobStartupReconciliation(jobs, executions);
    }

    private RemoteJob job(String id, JobStatus status, Instant started,
                          Instant submitted) {
        return new RemoteJob(id, "fetch-news", null,
                Map.of(), status, submitted, started, null, null, null, null);
    }

    private TaskExecution execution(String id, ExecutionStatus status,
                                    Instant started) {
        return new TaskExecution(id, "WEBSCRAPE", "ADDR-001", null,
                TriggerType.REMOTE, status, started, null, null, Map.of());
    }

    @Test
    void runningAndQueuedJobsFromPreviousBootAreFinalizedWithReason() {
        jobs.save(job("JOB-1", JobStatus.RUNNING, PAST, PAST));
        jobs.save(job("JOB-2", JobStatus.QUEUED, null, PAST));

        reconciler.reconcile();

        RemoteJob first = jobs.findById("JOB-1").orElseThrow();
        assertEquals(JobStatus.FAILED, first.status());
        assertTrue(first.error().contains("Server restarted while the job was RUNNING"));
        assertTrue(first.error().contains("US-18.10"));
        assertTrue(first.isTerminal());

        RemoteJob second = jobs.findById("JOB-2").orElseThrow();
        assertEquals(JobStatus.FAILED, second.status());
        assertTrue(second.error().contains("QUEUED"));
    }

    @Test
    void terminalJobsAreNeverTouched() {
        RemoteJob done = job("JOB-1", JobStatus.COMPLETED, PAST, PAST)
                .finish(JobStatus.COMPLETED, PAST.plusSeconds(60), "ok", null);
        jobs.save(done);
        RemoteJob cancelled = job("JOB-2", JobStatus.CANCELLED, PAST, PAST);
        jobs.save(cancelled);

        reconciler.reconcile();

        assertEquals(done, jobs.findById("JOB-1").orElseThrow());
        assertEquals(cancelled, jobs.findById("JOB-2").orElseThrow());
    }

    @Test
    void jobOfThisBootIsNeverTouched() {
        Instant afterBoot = reconciler.bootInstant().plusSeconds(5);
        RemoteJob current = job("JOB-1", JobStatus.RUNNING, afterBoot, afterBoot);
        jobs.save(current);

        reconciler.reconcile();

        assertEquals(current, jobs.findById("JOB-1").orElseThrow());
    }

    @Test
    void executionFromPreviousBootIsFinalized_thisBootIsNot() {
        executions.save(execution("EXEC-1", ExecutionStatus.RUNNING, PAST));
        executions.save(execution("EXEC-2", ExecutionStatus.SUCCESS, PAST));
        Instant afterBoot = reconciler.bootInstant().plusSeconds(5);
        executions.save(execution("EXEC-3", ExecutionStatus.RUNNING, afterBoot));

        reconciler.reconcile();

        TaskExecution orphan = executions.findById("EXEC-1").orElseThrow();
        assertEquals(ExecutionStatus.FAILED, orphan.status());
        assertTrue(orphan.outcome().contains("Server restarted while the execution was RUNNING"));
        assertEquals(ExecutionStatus.SUCCESS,
                executions.findById("EXEC-2").orElseThrow().status());
        assertEquals(ExecutionStatus.RUNNING,
                executions.findById("EXEC-3").orElseThrow().status());
    }

    @Test
    void lostCasReReadLeavesTheFreshStateIntact() {
        // the CAS loses because the job reached a terminal state between
        // the scan and the write; the reconciler must respect the fresh
        // record, not overwrite it with FAILED
        RemoteJob scanned = job("JOB-1", JobStatus.RUNNING, PAST, PAST);
        RemoteJob actuallyCompleted = scanned
                .finish(JobStatus.COMPLETED, PAST.plusSeconds(30), "done", null);
        jobs.save(actuallyCompleted);

        JobStartupReconciliation racing = new JobStartupReconciliation(
                new InMemoryJobStorePort() {
                    @Override
                    public List<RemoteJob> findAll() {
                        // what the scan saw: still RUNNING
                        return List.of(scanned);
                    }

                    @Override
                    public boolean saveIfStatus(String jobId, JobStatus expected,
                                                RemoteJob record) {
                        return false; // CAS loss: the record moved on
                    }
                },
                executions);

        racing.reconcile();

        assertEquals(actuallyCompleted, jobs.findById("JOB-1").orElseThrow());
        assertFalse(jobs.findById("JOB-1").orElseThrow().status() == JobStatus.FAILED);
    }

    @Test
    void boundaryStartedExactlyAtBootIsThisProcesssRecord() {
        Instant atBoot = reconciler.bootInstant();
        jobs.save(job("JOB-1", JobStatus.RUNNING, atBoot, atBoot));

        reconciler.reconcile();

        assertEquals(JobStatus.RUNNING, jobs.findById("JOB-1").orElseThrow().status());
    }
}
