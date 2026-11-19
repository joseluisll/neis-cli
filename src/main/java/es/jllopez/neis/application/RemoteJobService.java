package es.jllopez.neis.application;

import es.jllopez.neis.domain.model.Address;
import es.jllopez.neis.domain.model.Chain;
import es.jllopez.neis.domain.model.ChainTask;
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
import java.util.NoSuchElementException;
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
    private final ChainService chains;
    private final AddressCatalogService catalog;
    private final Executor jobRunner;
    /** The fetch chain neis_fetch_news wraps (US-18.4, issue #119). */
    private final String fetchChainName;
    /**
     * The in-flight job runners (US-18.7, issue #122): the handles a
     * cancel() interrupts. Removed on completion so the map never
     * grows unbounded.
     */
    private final java.util.concurrent.ConcurrentHashMap<String,
            CompletableFuture<?>> inFlight = new java.util.concurrent.ConcurrentHashMap<>();

    public RemoteJobService(JobStorePort jobs, ExecutionService executions,
                            ChainService chains, AddressCatalogService catalog,
                            @Qualifier("jobRunner") Executor jobRunner,
                            @org.springframework.beans.factory.annotation
                                    .Value("${neis.fetch.chain-name:core-chain-ssafp}")
                            String fetchChainName) {
        this.jobs = jobs;
        this.executions = executions;
        this.chains = chains;
        this.catalog = catalog;
        this.jobRunner = jobRunner;
        this.fetchChainName = fetchChainName;
    }

    /**
     * Submits a NEWS FETCH as a remote job (US-18.4, the neis_fetch_news
     * tool): the job wraps the configured fetch chain — created with
     * the SSAFP stages if it does not exist yet — over ONE address or
     * over ALL registered addresses. Returns the QUEUED job
     * immediately; the job result carries one outcome line per address.
     *
     * @throws IllegalArgumentException synchronously when there are no
     *         registered addresses (fetch-all) or the single address is
     *         unknown; IllegalStateException when the single address is
     *         not verified (US-18.4 review: eligibility has both shapes)
     */
    public RemoteJob submitFetchNews(String addressId) {
        // validation BEFORE the chain resolution (US-18.4 review): a
        // refused first-ever call must not leave a default chain behind
        List<String> targets;
        if (addressId != null) {
            // resolve-by-name FIRST without creating: an existing chain
            // validates the id; the creation (if needed) happens after
            // the address validation below
            Chain existing = chains.list().stream()
                    .filter(c -> fetchChainName.equals(c.name()))
                    .findFirst()
                    .orElse(null);
            if (existing != null) {
                executions.validateRemoteChainSubmission(existing.id(), addressId);
            } else {
                // no chain yet: the ADDRESS side of the pre-check runs
                // alone (the chain side is vacuous — it is created next)
                executions.validateRemoteAddress(addressId);
            }
            targets = List.of(addressId);
        } else {
            targets = catalog.list().stream().map(Address::id).toList();
            if (targets.isEmpty()) {
                throw new IllegalArgumentException(
                        "No registered addresses to fetch — register one "
                                + "first (neis_upsert_address)");
            }
        }
        Chain chain = resolveFetchChain();
        // re-validate AFTER resolution (US-18.4 re-review): the chain
        // may have been deleted between the pre-check and here — a
        // silently-recreated EMPTY chain would "succeed" doing nothing
        if (addressId != null) {
            executions.validateRemoteChainSubmission(chain.id(), addressId);
        } else if (chain.tasks().isEmpty()) {
            throw new IllegalStateException(
                    "The fetch chain '" + fetchChainName + "' has no steps");
        }
        RemoteJob job = new RemoteJob("JOB-" + UUID.randomUUID(),
                "fetch-news", addressId,
                Map.of("chain-id", chain.id(),
                        "addresses", String.join(",", targets)),
                JobStatus.QUEUED, Instant.now(), null, null, null, null, null);
        jobs.save(job);
        log.info("[JOB] {} submitted: fetch-news over {} address(es) "
                + "(chain {})", job.id(), targets.size(), chain.name());
        CompletableFuture<Void> runner = CompletableFuture.runAsync(
                () -> runFetchNewsJob(job.id(), chain.id(), targets), jobRunner)
                .whenComplete((v, e) -> {
                    inFlight.remove(job.id());
                    if (e != null) {
                        log.error("[JOB] {} runner failed: {}",
                                job.id(), e.toString());
                    }
                });
        inFlight.put(job.id(), runner);
        return job;
    }

    /**
     * A cancel request's outcome (US-18.7, issue #122): the job record
     * (updated to CANCELLED when the request changed something), whether
     * THIS request changed anything, and a caller-readable note.
     */
    public record CancellationOutcome(RemoteJob job, boolean cancelled,
                                      String note) {
    }

    /**
     * Cancels a queued or running remote job (US-18.7, issue #122). A
     * QUEUED job is marked CANCELLED before its run starts (the runner
     * aborts without executing); a RUNNING job is marked CANCELLED and
     * its runner thread INTERRUPTED — the run's remaining work records
     * the interruption and the job finalizes as CANCELLED, observably
     * different from FAILED. An already-finished job is a NO-OP with a
     * clear note, never an error. The UNDERLYING execution is not
     * cancelled (execution cancellation does not exist); the job record
     * is the observable contract.
     *
     * @throws java.util.NoSuchElementException unknown job id (404)
     */
    public CancellationOutcome cancel(String jobId) {
        RemoteJob job = jobs.findById(jobId).orElseThrow(() ->
                new NoSuchElementException("Unknown job: " + jobId));
        if (job.status().isTerminal()) {
            return new CancellationOutcome(job, false,
                    "already finished (" + job.status() + ") — nothing to cancel");
        }
        RemoteJob cancelled = job.finish(JobStatus.CANCELLED, Instant.now(),
                job.result(),
                "Cancelled by the caller while " + job.status());
        save(cancelled);
        log.info("[JOB] {} CANCELLED by the caller while {}",
                jobId, job.status());
        CompletableFuture<?> runner = inFlight.get(jobId);
        if (runner != null) {
            runner.cancel(true); // interrupts the pooled runner thread
        }
        return new CancellationOutcome(cancelled, true,
                "cancellation requested while " + job.status()
                        + " — the runner aborts at its next check");
    }

    /**
     * The configured fetch chain, created on first use (US-18.4): the
     * SSAFP stages in pipeline order. Resolution is by NAME so a
     * recreated store (new chain id) keeps working. First-use RACE
     * (US-18.4 review): two concurrent first submissions would both
     * see an empty list and the second create would fail with the
     * duplicate-name refusal — caught and re-resolved by name.
     */
    private Chain resolveFetchChain() {
        return chains.list().stream()
                .filter(c -> fetchChainName.equals(c.name()))
                .findFirst()
                .orElseGet(() -> {
                    log.info("[JOB] creating the default fetch chain '{}'",
                            fetchChainName);
                    try {
                        return chains.create(fetchChainName,
                                "Default fetch chain (created for the "
                                        + "neis_fetch_news tool)",
                                defaultFetchSteps());
                    } catch (IllegalStateException raced) {
                        // another submission created it concurrently
                        log.info("[JOB] default fetch chain '{}' created "
                                + "concurrently — re-resolving", fetchChainName);
                        return chains.list().stream()
                                .filter(c -> fetchChainName.equals(c.name()))
                                .findFirst()
                                .orElseThrow(() -> raced);
                    }
                });
    }

    /** The SSAFP stages in pipeline order (the default fetch chain). */
    static List<es.jllopez.neis.domain.model.ChainTask> defaultFetchSteps() {
        return List.of(
                new es.jllopez.neis.domain.model.ChainTask(1, "WEBSCRAPE", Map.of()),
                new es.jllopez.neis.domain.model.ChainTask(2, "SIMPLIFY", Map.of()),
                new es.jllopez.neis.domain.model.ChainTask(3, "ANALYZE", Map.of()),
                new es.jllopez.neis.domain.model.ChainTask(4, "FILTER", Map.of()),
                new es.jllopez.neis.domain.model.ChainTask(5, "PRINT", Map.of()));
    }

    /**
     * Submits a single-task run as a remote job and returns the QUEUED
     * job immediately.
     *
     * @throws IllegalArgumentException synchronously (nothing is
     *         persisted) when the pre-check refuses an unknown task
     *         type or an unknown/ineligible address (US-18.1 review
     *         finding, issue #127); refusals that arise LATER — on the
     *         runner, e.g. the address was removed between the
     *         pre-check and the run — are still recorded on the job
     *         (FAILED), so a caller that only polls sees the reason.
     */
    public RemoteJob submitRunTask(String taskCode, String addressId,
                                   Map<String, String> parameters) {
        executions.validateRemoteSubmission(taskCode, addressId);
        // Remote callers can send null values; Map.copyOf (the domain's
        // immutability guard) rejects them — nulls are dropped HERE so
        // the sanitization happens at the boundary, not as an NPE after
        // the job record was persisted.
        Map<String, String> sanitized = new LinkedHashMap<>();
        if (parameters != null) {
            parameters.forEach((key, value) -> {
                if (value != null) {
                    sanitized.put(key, value);
                }
            });
        }
        Map<String, String> jobParams = new LinkedHashMap<>();
        jobParams.put("task-code", taskCode);
        jobParams.putAll(sanitized);
        return submit("run-task", addressId, Map.copyOf(jobParams),
                () -> executions.submit(taskCode, addressId, TriggerType.REMOTE,
                        sanitized));
    }

    /**
     * Submits a chain run as a remote job; same contract as
     * {@link #submitRunTask} (synchronous pre-check included).
     */
    public RemoteJob submitRunChain(String chainId, String addressId) {
        executions.validateRemoteChainSubmission(chainId, addressId);
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
        CompletableFuture<Void> runner = CompletableFuture.runAsync(
                () -> runJob(job.id(), run), jobRunner)
                .whenComplete((v, e) -> {
                    inFlight.remove(job.id());
                    if (e != null) {
                        log.error("[JOB] {} runner failed: {}", job.id(), e.toString());
                    }
                });
        inFlight.put(job.id(), runner);
        return job;
    }

    /**
     * The fetch-news job runner (US-18.4): the chain runs per address
     * through the execution layer (each run a REMOTE execution with its
     * own record and provenance), one outcome line per address collected
     * into the job result. COMPLETED only when EVERY address succeeded —
     * partial batches are FAILED with the per-address outcomes as the
     * reason (an honest summary beats a silently-green batch). Per
     * runJob, every state transition is failure-guarded.
     */
    private void runFetchNewsJob(String jobId, String chainId,
                                 List<String> addressIds) {
        RemoteJob job;
        try {
            job = jobs.findById(jobId).orElse(null);
        } catch (RuntimeException storeDown) {
            log.error("[JOB] {}: could not read the job before its run: {}",
                    jobId, storeDown.toString());
            return;
        }
        if (job == null) {
            log.error("[JOB] {} vanished before its runner started", jobId);
            return;
        }
        // US-18.7 (issue #122): a job CANCELLED while QUEUED never runs —
        // the cancel() write wins over the runner's pickup
        if (job.status() == JobStatus.CANCELLED) {
            log.info("[JOB] {} cancelled while QUEUED — runner aborts", jobId);
            return;
        }
        job = save(job.transition(JobStatus.RUNNING, Instant.now(), null));
        StringBuilder outcomes = new StringBuilder();
        boolean allSuccess = true;
        String firstExecutionId = null;
        boolean interrupted = false;
        try {
            for (int i = 0; i < addressIds.size(); i++) {
                String addressId = addressIds.get(i);
                // US-18.4 re-review: CONSUME the interrupt flag once per
                // job — jobRunner threads are POOLED, and a flag left set
                // by a previous job would fail every future fetch landing
                // on that worker
                if (Thread.interrupted()) {
                    interrupted = true;
                }
                if (interrupted) {
                    interrupted = true;
                    outcomes.append(addressId)
                            .append(": SKIPPED — job runner interrupted before "
                                    + "its run; ");
                    allSuccess = false;
                    continue;
                }
                String line;
                try {
                    TaskExecution execution = executions.submitChain(
                            chainId, addressId, TriggerType.REMOTE);
                    if (firstExecutionId == null) {
                        firstExecutionId = execution.id();
                        job = save(job.withExecution(execution.id()));
                    }
                    TaskExecution finalRecord = awaitTerminal(execution.id());
                    if (finalRecord == null || !finalRecord.status().isTerminal()) {
                        line = addressId + ": FAILED (execution "
                                + execution.id() + " interrupted or vanished)";
                        allSuccess = false;
                    } else {
                        line = addressId + ": " + finalRecord.status()
                                + " — " + firstLine(finalRecord.outcome());
                        if (finalRecord.status() != ExecutionStatus.SUCCESS) {
                            allSuccess = false;
                        }
                    }
                } catch (RuntimeException refused) {
                    line = addressId + ": SKIPPED — "
                            + firstLine(refused.getMessage());
                    allSuccess = false;
                }
                outcomes.append(line).append("; ");
                log.info("[JOB] {} fetch-news: {}", jobId, line);
            }
            // US-18.7: the cancel() write may have raced in during the
            // batch — the job record is authoritative over the loop's
            // own tally, and CANCELLED must not be overwritten; the
            // collected per-address outcomes are preserved as the result
            RemoteJob current = jobs.findById(jobId).orElse(job);
            if (current.status() == JobStatus.CANCELLED) {
                save(current.finish(JobStatus.CANCELLED, Instant.now(),
                        truncate(outcomes.toString()), current.error()));
                log.info("[JOB] {} CANCELLED during the batch — partial "
                        + "outcomes kept", jobId);
                return;
            }
            if (allSuccess) {
                save(job.finish(JobStatus.COMPLETED, Instant.now(),
                        truncate(outcomes.toString()), null));
                log.info("[JOB] {} COMPLETED ({} addresses)", jobId,
                        addressIds.size());
            } else {
                save(job.finish(JobStatus.FAILED, Instant.now(), null,
                        truncate(outcomes.toString())));
                log.info("[JOB] {} FAILED (batch had non-success outcomes)",
                        jobId);
            }
        } catch (Throwable t) {
            String error = t.getClass().getSimpleName()
                    + (t.getMessage() != null ? ": " + t.getMessage() : "");
            // US-18.7: a cancel() racing this batch wins over the tally
            RemoteJob current = jobs.findById(jobId).orElse(job);
            if (current.status() == JobStatus.CANCELLED) {
                save(current.finish(JobStatus.CANCELLED, Instant.now(),
                        truncate(outcomes.toString()), current.error()));
                log.info("[JOB] {} CANCELLED (refusal during batch: {})",
                        jobId, error);
            } else {
                save(job.finish(JobStatus.FAILED, Instant.now(), null,
                        truncate(error + " | " + outcomes)));
                log.info("[JOB] {} FAILED before/at run: {}", jobId, error);
            }
            if (t instanceof Error errorT) {
                throw errorT;
            }
        }
    }

    /** The outcome's first line (chain outcomes are long, ';'-joined). */
    private String firstLine(String outcome) {
        if (outcome == null || outcome.isBlank()) {
            return "(no outcome recorded)";
        }
        int cut = outcome.indexOf('\n');
        return cut > 0 ? outcome.substring(0, cut) : outcome;
    }

    private void runJob(String jobId, Supplier<TaskExecution> run) {
        // The initial read is INSIDE the guarded region: a transient
        // store failure here (SQLITE_BUSY was observed live) would
        // otherwise strand the job in QUEUED forever — every state
        // transition of the job must be failure-guarded.
        RemoteJob job;
        try {
            job = jobs.findById(jobId).orElse(null);
        } catch (RuntimeException storeDown) {
            log.error("[JOB] {}: could not read the job before its run: {}",
                    jobId, storeDown.toString());
            return;
        }
        if (job == null) {
            log.error("[JOB] {} vanished before its runner started", jobId);
            return;
        }
        // US-18.7 (issue #122): a job CANCELLED while QUEUED never runs —
        // the cancel() write wins over the runner's pickup
        if (job.status() == JobStatus.CANCELLED) {
            log.info("[JOB] {} cancelled while QUEUED — runner aborts", jobId);
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
            } else if (!finalRecord.status().isTerminal()) {
                // the runner thread was interrupted mid-wait (context
                // shutdown, or a cancel() of this job — the record is
                // authoritative): a terminal job always carries a reason
                RemoteJob current = jobs.findById(jobId).orElse(job);
                if (current.status() == JobStatus.CANCELLED) {
                    save(current.finish(JobStatus.CANCELLED, Instant.now(),
                            null, current.error()));
                    log.info("[JOB] {} CANCELLED while waiting for execution "
                            + "{}", jobId, execution.id());
                } else {
                    save(job.finish(JobStatus.FAILED, Instant.now(), null,
                            "Job runner interrupted while waiting for execution "
                                    + execution.id()));
                    log.info("[JOB] {} FAILED: runner interrupted", jobId);
                }
            } else if (finalRecord.status() == ExecutionStatus.SUCCESS) {
                // US-18.7: the cancel() write may have raced in during the
                // run — the job record is authoritative over the outcome;
                // CANCELLED must not be overwritten by COMPLETED
                RemoteJob current = jobs.findById(jobId).orElse(job);
                if (current.status() == JobStatus.CANCELLED) {
                    save(current.finish(JobStatus.CANCELLED, Instant.now(),
                            truncate(finalRecord.outcome()), current.error()));
                    log.info("[JOB] {} CANCELLED (the execution finished "
                            + "meanwhile; outcome kept)", jobId);
                } else {
                    save(job.finish(JobStatus.COMPLETED, Instant.now(),
                            truncate(finalRecord.outcome()), null));
                    log.info("[JOB] {} COMPLETED (execution {})", jobId, execution.id());
                }
            } else {
                // the execution reached terminal FAILED — same race guard:
                // a cancel() that landed during the run must survive
                RemoteJob current = jobs.findById(jobId).orElse(job);
                if (current.status() == JobStatus.CANCELLED) {
                    save(current.finish(JobStatus.CANCELLED, Instant.now(),
                            truncate(finalRecord.outcome()), current.error()));
                    log.info("[JOB] {} CANCELLED (the execution failed "
                            + "meanwhile; outcome kept)", jobId);
                } else {
                    save(job.finish(JobStatus.FAILED, Instant.now(), null,
                            truncate(finalRecord.outcome())));
                    log.info("[JOB] {} FAILED (execution {}): {}", jobId,
                            execution.id(), finalRecord.outcome());
                }
            }
        } catch (Throwable t) {
            String error = t.getClass().getSimpleName()
                    + (t.getMessage() != null ? ": " + t.getMessage() : "");
            // US-18.7: an interrupt surfacing as an exception during a
            // cancelled job must not overwrite the CANCELLED record
            RemoteJob current = jobs.findById(jobId).orElse(job);
            if (current.status() == JobStatus.CANCELLED) {
                save(current.finish(JobStatus.CANCELLED, Instant.now(),
                        current.result(), current.error()));
                log.info("[JOB] {} CANCELLED (refusal during run: {})",
                        jobId, error);
            } else {
                save(job.finish(JobStatus.FAILED, Instant.now(), null,
                        truncate(error)));
                log.info("[JOB] {} FAILED before/at run: {}", jobId, error);
            }
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
