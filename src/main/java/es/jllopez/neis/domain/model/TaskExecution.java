package es.jllopez.neis.domain.model;

import java.time.Instant;
import java.util.Map;

/**
 * One execution record (US-05.3, issue #26): every task run — manual
 * or scheduled — leaves one, with status, timestamps and outcome, for
 * traceability. Immutable snapshot; the executor replaces it through
 * the store as the run progresses.
 *
 * <p>{@code chainId} links a record to the chain definition it ran
 * (US-08, epic #8); null for single-task runs. A chain run's
 * {@code taskCode} is CHAIN, the runnable kind the record shows.</p>
 *
 * <p>{@code parameters} is the run's input parameter map (US-08.3:
 * selector override, {@code max-items} cap, WEBSCRAPE filters) —
 * persisted with the record (V12, US-16.3 review finding #97) so the
 * run detail shows what the run was given and a re-run is faithful
 * (it resubmits the same parameters, never defaults). Empty for runs
 * without parameters; chain runs carry their per-step parameters in
 * the chain definition, so their record map is empty.</p>
 */
public record TaskExecution(String id, String taskCode, String addressId, String chainId,
                            TriggerType triggerType, ExecutionStatus status,
                            Instant startedAt, Instant finishedAt, String outcome,
                            Map<String, String> parameters) {

    public TaskExecution {
        if (status == null) {
            status = ExecutionStatus.PENDING;
        }
        parameters = parameters == null ? Map.of() : Map.copyOf(parameters);
    }

    /** The pre-V12 shape: a run without parameters. */
    public TaskExecution(String id, String taskCode, String addressId, String chainId,
                         TriggerType triggerType, ExecutionStatus status,
                         Instant startedAt, Instant finishedAt, String outcome) {
        this(id, taskCode, addressId, chainId, triggerType, status,
                startedAt, finishedAt, outcome, Map.of());
    }

    public TaskExecution with(ExecutionStatus newStatus, Instant finished, String result) {
        return new TaskExecution(id, taskCode, addressId, chainId, triggerType,
                newStatus, startedAt, finished, result, parameters);
    }
}
