package es.jllopez.neis.infrastructure.persistence;

import es.jllopez.neis.domain.model.ExecutionStatus;
import es.jllopez.neis.domain.model.TriggerType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * JPA mapping of an execution record (US-05.3, issue #26) onto the
 * {@code executions} table of schema V1. Infrastructure-only: the
 * domain sees the {@link es.jllopez.neis.domain.model.TaskExecution}
 * snapshot through the execution-store connector. Timestamps are
 * ISO-8601 TEXT per the schema's SQLite storage format.
 */
@Entity
@Table(name = "executions")
public class ExecutionEntity {

    @Id
    private String id;

    @Column(name = "chain_id")
    private String chainId;

    @Column(name = "task_code", nullable = false)
    private String taskCode;

    @Column(name = "address_id")
    private String addressId;

    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_type", nullable = false)
    private TriggerType triggerType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ExecutionStatus status;

    /** ISO-8601 TEXT (the schema's storage format for SQLite). */
    @Column(name = "started_at")
    private String startedAt;

    /** ISO-8601 TEXT (the schema's storage format for SQLite). */
    @Column(name = "finished_at")
    private String finishedAt;

    @Column(length = 4096)
    private String outcome;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getChainId() {
        return chainId;
    }

    public void setChainId(String chainId) {
        this.chainId = chainId;
    }

    public String getTaskCode() {
        return taskCode;
    }

    public void setTaskCode(String taskCode) {
        this.taskCode = taskCode;
    }

    public String getAddressId() {
        return addressId;
    }

    public void setAddressId(String addressId) {
        this.addressId = addressId;
    }

    public TriggerType getTriggerType() {
        return triggerType;
    }

    public void setTriggerType(TriggerType triggerType) {
        this.triggerType = triggerType;
    }

    public ExecutionStatus getStatus() {
        return status;
    }

    public void setStatus(ExecutionStatus status) {
        this.status = status;
    }

    public String getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(String startedAt) {
        this.startedAt = startedAt;
    }

    public String getFinishedAt() {
        return finishedAt;
    }

    public void setFinishedAt(String finishedAt) {
        this.finishedAt = finishedAt;
    }

    public String getOutcome() {
        return outcome;
    }

    public void setOutcome(String outcome) {
        this.outcome = outcome;
    }
}
