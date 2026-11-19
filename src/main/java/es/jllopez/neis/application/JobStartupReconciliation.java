package es.jllopez.neis.application;

import es.jllopez.neis.domain.model.ExecutionStatus;
import es.jllopez.neis.domain.model.JobStatus;
import es.jllopez.neis.domain.model.RemoteJob;
import es.jllopez.neis.domain.model.TaskExecution;
import es.jllopez.neis.domain.port.ExecutionStorePort;
import es.jllopez.neis.domain.port.JobStorePort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Job-state reconciliation on server startup (US-18.10, issue #133):
 * jobs and executions left in a non-terminal state by a PREVIOUS
 * process (restart mid-run, crash, SIGKILL) are finalized with an
 * explicit reason instead of staying RUNNING forever — no runner of
 * this process will ever pick them up (the live incident: a fetch job
 * RUNNING across a service restart read terminal:false indefinitely).
 *
 * <p>Runs as a {@link SmartLifecycle} at phase 0, i.e. BEFORE the web
 * server lifecycle (default phase {@code Integer.MAX_VALUE}) — remote
 * adapters start serving after the store is reconciled. The scheduler
 * (registered at bean-init time) can in principle fire inside the
 * init→reconcile window; the {@code bootInstant} guard skips every
 * record with a startedAt at-or-after the boot, so only records from
 * the vanished process are touched. Residual window, documented: a
 * PENDING execution with a null startedAt created by THIS process in
 * the milliseconds before reconcile is indistinguishable from an
 * orphan (the execution record carries no created-time column; a
 * schema change is out of scope here) — the window is milliseconds
 * and the effect is one false FAILED on a just-submitted cron run.</p>
 *
 * <p>Job finalizes are compare-and-set guarded
 * ({@link JobStorePort#saveIfStatus}) so a record that legitimately
 * moved on in the same window is never clobbered; on a lost CAS the
 * record is re-read and the fresh state wins. Execution records have
 * no CAS (the executor's own lifecycle writes go through plain
 * saves); at reconcile time no run of a previous process can
 * progress, so a plain save with the bootInstant guard is sound.</p>
 */
@Component
public class JobStartupReconciliation implements SmartLifecycle {

    private static final Logger log =
            LoggerFactory.getLogger(JobStartupReconciliation.class);

    private final JobStorePort jobs;
    private final ExecutionStorePort executions;
    /** Records started at-or-after this instant belong to THIS process. */
    private final Instant bootInstant;

    private volatile boolean running;

    public JobStartupReconciliation(JobStorePort jobs,
                                    ExecutionStorePort executions) {
        this.jobs = jobs;
        this.executions = executions;
        this.bootInstant = Instant.now();
    }

    @Override
    public void start() {
        running = true;
        reconcile();
    }

    @Override
    public void stop() {
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /**
     * Finalizes every non-terminal job (CAS-guarded) and execution
     * (bootInstant-guarded) from a previous process. Public so the
     * regression tests can drive it against the in-memory doubles.
     */
    public synchronized void reconcile() {
        int reconciled = reconcileJobs() + reconcileExecutions();
        if (reconciled > 0) {
            log.info("[RECONCILE] {} orphaned record(s) from a previous "
                    + "process finalized", reconciled);
        }
    }

    private int reconcileJobs() {
        int count = 0;
        for (RemoteJob job : jobs.findAll()) {
            if (job.isTerminal() || !precedesBoot(job.startedAt(), job.submittedAt())) {
                continue;
            }
            RemoteJob finalized = job.finish(JobStatus.FAILED,
                    Instant.now(), null,
                    "Server restarted while the job was " + job.status()
                            + " — the run did not survive the restart (US-18.10)");
            if (jobs.saveIfStatus(job.id(), job.status(), finalized)) {
                log.info("[RECONCILE] job {} {} -> FAILED (orphaned by a "
                        + "restart; executionId={})", job.id(), job.status(),
                        job.executionId());
                count++;
            } else {
                // re-read on lost CAS: the fresh state wins, never ours
                RemoteJob fresh = jobs.findById(job.id()).orElse(job);
                if (!fresh.isTerminal()) {
                    log.warn("[RECONCILE] job {} could not be finalized "
                            + "(status moved to {} mid-reconcile)", job.id(),
                            fresh.status());
                }
            }
        }
        return count;
    }

    private int reconcileExecutions() {
        int count = 0;
        for (TaskExecution execution : executions.findAll()) {
            if (execution.status().isTerminal() || !precedesBoot(execution.startedAt(), null)) {
                continue;
            }
            executions.save(execution.with(ExecutionStatus.FAILED,
                    Instant.now(),
                    "Server restarted while the execution was "
                            + execution.status()
                            + " — the run did not survive the restart (US-18.10)"));
            log.info("[RECONCILE] execution {} {} -> FAILED (orphaned by a "
                    + "restart)", execution.id(), execution.status());
            count++;
        }
        return count;
    }

    /**
     * A record belongs to the vanished process when its run started
     * before this boot (jobs: fall back to the submission time — a
     * QUEUED job was never picked up). Null timestamps mean the run
     * never got that far, i.e. it predates this boot's runner.
     */
    private boolean precedesBoot(Instant startedAt, Instant submittedAt) {
        Instant marker = startedAt != null ? startedAt : submittedAt;
        return marker == null || marker.isBefore(bootInstant);
    }

    /** Visible to tests only (asserts the boot-guard boundary). */
    Instant bootInstant() {
        return bootInstant;
    }
}
