package es.jllopez.neis.infrastructure.persistence;

import es.jllopez.neis.domain.model.JobStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * JPA mapping of a remote job (US-18.2, issue #117) onto the
 * {@code remote_jobs} table of schema V19. Infrastructure-only: the
 * domain sees the {@link es.jllopez.neis.domain.model.RemoteJob}
 * snapshot through the job-store connector. Timestamps are ISO-8601
 * TEXT per the schema's SQLite storage format; {@code parameters} is
 * a JSON object like the executions table's V12 column.
 */
@Entity
@Table(name = "remote_jobs")
public class RemoteJobEntity {

    @Id
    private String id;

    @Column(nullable = false)
    private String operation;

    @Column(name = "address_id")
    private String addressId;

    /** JSON object of the job's input parameters; empty map default. */
    @Column
    private String parameters;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private JobStatus status;

    /** ISO-8601 TEXT (the schema's storage format for SQLite). */
    @Column(name = "submitted_at", nullable = false)
    private String submittedAt;

    /** ISO-8601 TEXT. */
    @Column(name = "started_at")
    private String startedAt;

    /** ISO-8601 TEXT. */
    @Column(name = "finished_at")
    private String finishedAt;

    /** The execution record this job produced (run operations). */
    @Column(name = "execution_id")
    private String executionId;

    /** The wrapped operation's outcome payload. */
    @Column
    private String result;

    /** The failure reason for FAILED jobs. */
    @Column
    private String error;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getOperation() {
        return operation;
    }

    public void setOperation(String operation) {
        this.operation = operation;
    }

    public String getAddressId() {
        return addressId;
    }

    public void setAddressId(String addressId) {
        this.addressId = addressId;
    }

    public String getParameters() {
        return parameters;
    }

    public void setParameters(String parameters) {
        this.parameters = parameters;
    }

    public JobStatus getStatus() {
        return status;
    }

    public void setStatus(JobStatus status) {
        this.status = status;
    }

    public String getSubmittedAt() {
        return submittedAt;
    }

    public void setSubmittedAt(String submittedAt) {
        this.submittedAt = submittedAt;
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

    public String getExecutionId() {
        return executionId;
    }

    public void setExecutionId(String executionId) {
        this.executionId = executionId;
    }

    public String getResult() {
        return result;
    }

    public void setResult(String result) {
        this.result = result;
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }
}
