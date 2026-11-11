package es.jllopez.neis.domain.model;

/**
 * The trigger of a task run (US-05, epic #5): MANUAL when a user asks
 * for it on demand (US-05.4), SCHEDULED when the scheduler fires it
 * (US-05.2). Stored in the {@code executions.trigger_type} column of
 * schema V1.
 */
public enum TriggerType {
    MANUAL,
    SCHEDULED
}
