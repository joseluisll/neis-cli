package es.jllopez.neis.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * JPA mapping of a stored content artifact (US-11.2, issue #42) onto
 * the {@code content_records} table of schema V1. Infrastructure-only:
 * the domain sees the
 * {@link es.jllopez.neis.domain.model.ContentRecord} snapshot through
 * the content-store connector. Timestamps are ISO-8601 TEXT per the
 * schema's SQLite storage format.
 */
@Entity
@Table(name = "content_records")
public class ContentRecordEntity {

    @Id
    private String id;

    @Column(name = "address_id", nullable = false)
    private String addressId;

    @Column(name = "capture_id", nullable = false)
    private String captureId;

    @Column(name = "task_code", nullable = false)
    private String taskCode;

    @Column(nullable = false)
    private String kind;

    /** The deterministic filesystem location; unique across the root. */
    @Column(nullable = false, unique = true)
    private String path;

    /** ISO-8601 TEXT (the schema's storage format for SQLite). */
    @Column(name = "created_at", nullable = false)
    private String createdAt;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getAddressId() {
        return addressId;
    }

    public void setAddressId(String addressId) {
        this.addressId = addressId;
    }

    public String getCaptureId() {
        return captureId;
    }

    public void setCaptureId(String captureId) {
        this.captureId = captureId;
    }

    public String getTaskCode() {
        return taskCode;
    }

    public void setTaskCode(String taskCode) {
        this.taskCode = taskCode;
    }

    public String getKind() {
        return kind;
    }

    public void setKind(String kind) {
        this.kind = kind;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(String createdAt) {
        this.createdAt = createdAt;
    }

    public Instant createdAtInstant() {
        return createdAt == null ? null : Instant.parse(createdAt);
    }
}
