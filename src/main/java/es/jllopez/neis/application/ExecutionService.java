package es.jllopez.neis.application;

import es.jllopez.neis.domain.model.Address;
import es.jllopez.neis.domain.model.Chain;
import es.jllopez.neis.domain.model.ChainTask;
import es.jllopez.neis.domain.model.ExecutionStatus;
import es.jllopez.neis.domain.model.TaskExecution;
import es.jllopez.neis.domain.model.TriggerType;
import es.jllopez.neis.domain.port.ChainStorePort;
import es.jllopez.neis.domain.port.ExecutionStorePort;
import es.jllopez.neis.domain.service.UrlSyntaxValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/**
 * Use case: the asynchronous task executor (US-05.1, issue #24). Every
 * run — manual (US-05.4) or scheduled (US-05.2) — goes through here:
 * it validates the task type and the address eligibility (US-04.3),
 * writes the execution record, runs the work on a background executor
 * so the caller never blocks, and drives the record's lifecycle
 * (PENDING -> RUNNING -> SUCCESS/FAILED, US-05.3).
 */
@Service
public class ExecutionService {

    private static final Logger log = LoggerFactory.getLogger(ExecutionService.class);

    private final ExecutionStorePort store;
    private final ChainStorePort chains;
    private final AddressCatalogService catalog;
    private final UrlSyntaxValidator urlValidator;
    private final TaskWorkService taskWork;
    private final Executor executor;
    private final Duration awaitTimeout;
    /** Per-address serialization: one lock object per registered address id. */
    private final Map<String, Object> addressLocks = new ConcurrentHashMap<>();

    /** The execution-record outcome column is 4096 chars; stay under it. */
    private static final int OUTCOME_LIMIT = 4000;

    public ExecutionService(ExecutionStorePort store,
                            ChainStorePort chains,
                            AddressCatalogService catalog,
                            UrlSyntaxValidator urlValidator,
                            TaskWorkService taskWork,
                            Executor taskExecutor,
                            @Value("${neis.execution.await-timeout-seconds:300}") long awaitTimeout) {
        this.store = store;
        this.chains = chains;
        this.catalog = catalog;
        this.urlValidator = urlValidator;
        this.taskWork = taskWork;
        this.executor = taskExecutor;
        this.awaitTimeout = Duration.ofSeconds(awaitTimeout);
    }

    /**
     * Submits a task run over a registered address and returns
     * immediately with the PENDING record.
     *
     * @throws IllegalArgumentException for an unknown task type or
     *                                  unknown/ineligible address (US-04.3)
     */
    public TaskExecution submit(String taskCode, String addressId, TriggerType trigger) {
        requireKnownTaskCode(taskCode);
        Address address = catalog.byId(addressId); // unknown ids refuse here
        requireEligible(address);
        TaskExecution record = new TaskExecution(
                "EXEC-" + UUID.randomUUID(),
                taskCode, address.id(), null,
                trigger, ExecutionStatus.PENDING, Instant.now(), null, null);
        store.save(record);
        log.info("Execution {} submitted: {} over {} ({})", record.id(), taskCode,
                address.id(),
                trigger == TriggerType.SCHEDULED ? "scheduled" : "manual");
        CompletableFuture.runAsync(() -> run(record, address), executor)
                .whenComplete((v, e) -> {
                    if (e != null) {
                        log.error("Execution {} runner failed: {}", record.id(), e.toString());
                    }
                });
        return record;
    }

    /**
     * Submits a CHAIN run over a registered address (US-08.2, issue
     * #34): the record carries the chain id, the work runs the
     * chain's tasks sequentially in position order.
     *
     * @throws IllegalArgumentException for an unknown chain or
     *                                  unknown/ineligible address (US-04.3)
     */
    public TaskExecution submitChain(String chainId, String addressId, TriggerType trigger) {
        Chain chain = chains.findById(chainId).orElseThrow(
                () -> new IllegalArgumentException("Unknown chain: " + chainId));
        Address address = catalog.byId(addressId); // unknown ids refuse here
        requireEligible(address);
        TaskExecution record = new TaskExecution(
                "EXEC-" + UUID.randomUUID(),
                "CHAIN", address.id(), chain.id(),
                trigger, ExecutionStatus.PENDING, Instant.now(), null, null);
        store.save(record);
        log.info("Execution {} submitted: chain '{}' ({} tasks) over {} ({})",
                record.id(), chain.name(), chain.tasks().size(), address.id(),
                trigger == TriggerType.SCHEDULED ? "scheduled" : "manual");
        CompletableFuture.runAsync(() -> run(record, address), executor)
                .whenComplete((v, e) -> {
                    if (e != null) {
                        log.error("Execution {} runner failed: {}", record.id(), e.toString());
                    }
                });
        return record;
    }

    /** Waits for the execution to finish; the final record or null on timeout. */
    public TaskExecution await(String executionId) {
        long deadline = System.nanoTime() + awaitTimeout.toNanos();
        while (System.nanoTime() < deadline) {
            TaskExecution current = store.findById(executionId).orElseThrow(
                    () -> new IllegalArgumentException("Unknown execution: " + executionId));
            if (current.status() == ExecutionStatus.SUCCESS
                    || current.status() == ExecutionStatus.FAILED) {
                return current;
            }
            try {
                TimeUnit.MILLISECONDS.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return current;
            }
        }
        return null; // still running past the timeout
    }

    /** All execution records, newest first (US-05.3 traceability). */
    public List<TaskExecution> list() {
        return store.findAll();
    }

    /** One execution record. */
    public TaskExecution show(String id) {
        return store.findById(id).orElseThrow(
                () -> new IllegalArgumentException("Unknown execution: " + id));
    }

    /** The task-type vocabulary the scheduler and CLI may use. */
    public List<String> taskTypes() {
        return store.taskTypes();
    }

    private void run(TaskExecution record, Address address) {
        // Every store failure below is caught and logged: the lifecycle
        // must never strand the record — a failure at the RUNNING
        // transition used to escape run() and lose the outcome forever
        // (slice-3 review finding, reproduced empirically).
        trySave(record.with(ExecutionStatus.RUNNING, null, null), "RUNNING transition");
        String outcome;
        ExecutionStatus finalStatus;
        try {
            // Same-address runs serialize: the per-address work tree
            // and the shared selector store are not safe for
            // concurrent runs (slice-3 review finding).
            synchronized (addressLocks.computeIfAbsent(address.id(), key -> new Object())) {
                outcome = record.chainId() != null
                        ? runChain(record.chainId(), address)
                        : taskWork.execute(record.taskCode(), address, Map.of(), null);
            }
            finalStatus = ExecutionStatus.SUCCESS;
        } catch (Throwable t) {
            outcome = describe(t);
            finalStatus = ExecutionStatus.FAILED;
            trySave(record.with(finalStatus, Instant.now(), outcome),
                    "FAILED transition");
            log.info("Execution {} FAILED: {}", record.id(), outcome);
            if (t instanceof Error error) {
                // Re-throw Errors after recording: the JVM state is not
                // trustworthy and must not be swallowed.
                throw error;
            }
            return;
        }
        trySave(record.with(finalStatus, Instant.now(), outcome), finalStatus + " transition");
        log.info("Execution {}: {}", record.id(), outcome);
    }

    private void trySave(TaskExecution record, String what) {
        try {
            store.save(record);
        } catch (RuntimeException e) {
            log.error("Execution {}: could not record the {} (outcome may be lost): {}",
                    record.id(), what, record.outcome(), e);
        }
    }

    /**
     * Runs a chain's tasks sequentially in position order (US-08.2,
     * issue #34), each with its own parameters (US-08.3, issue #35).
     * The chain-level exception handler (US-08.4, issue #36) turns a
     * failing step into a ChainExecutionException whose message names
     * the failed step and carries the steps completed so far; run()
     * records it as the execution's FAILED outcome. Steps after the
     * failure do NOT run, and Errors propagate raw (the run() catch
     * records and re-throws them).
     */
    private String runChain(String chainId, Address address) {
        Chain chain = chains.findById(chainId).orElseThrow(
                () -> new IllegalStateException("Chain vanished mid-run: " + chainId));
        List<ChainTask> tasks = chain.tasks();
        StringBuilder outcome = new StringBuilder();
        for (int i = 0; i < tasks.size(); i++) {
            ChainTask task = tasks.get(i);
            try {
                String stepOutcome = taskWork.execute(task.taskCode(), address,
                        task.parameters(), chain.name());
                outcome.append("step ").append(i + 1).append('/').append(tasks.size())
                        .append(' ').append(task.taskCode()).append(": ")
                        .append(stepOutcome);
                if (i < tasks.size() - 1) {
                    outcome.append("; ");
                }
                log.info("Execution over {}: chain '{}' step {}/{} {} succeeded",
                        address.id(), chain.name(), i + 1, tasks.size(), task.taskCode());
            } catch (Throwable t) {
                if (t instanceof Error error) {
                    throw error; // recorded and re-thrown by the run() catch
                }
                throw new ChainExecutionException("chain '" + chain.name()
                        + "' failed at step " + (i + 1) + '/' + tasks.size()
                        + " (" + task.taskCode() + "): " + describe(t)
                        + (i > 0 ? "; completed: " + outcome : ""), t);
            }
        }
        return truncate(outcome.toString());
    }

    /** Chain-level failure (US-08.4): names the failed step. */
    static final class ChainExecutionException extends RuntimeException {
        ChainExecutionException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private String truncate(String outcome) {
        return outcome.length() <= OUTCOME_LIMIT
                ? outcome : outcome.substring(0, OUTCOME_LIMIT) + "…";
    }

    private String describe(Throwable t) {
        return t.getClass().getSimpleName()
                + (t.getMessage() != null ? ": " + t.getMessage() : "");
    }

    private void requireKnownTaskCode(String taskCode) {
        if (taskCode == null || !store.taskTypes().contains(taskCode)) {
            throw new IllegalArgumentException("Unknown task type: " + taskCode
                    + " (known: " + store.taskTypes() + ")");
        }
    }

    /** US-04.3: only syntactically valid, VERIFIED addresses are eligible. */
    private void requireEligible(Address address) {
        String invalid = urlValidator.validate(address.url()).orElse(null);
        if (invalid != null) {
            throw new IllegalArgumentException("Address " + address.id()
                    + " is not eligible: URL is invalid — " + invalid);
        }
        if (!address.isVerified()) {
            throw new IllegalStateException("Address " + address.id()
                    + " is not eligible: verification status is "
                    + address.verificationStatus()
                    + " — verify it first with 'address verify'");
        }
    }
}
