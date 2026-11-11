package es.jllopez.neis.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * JPA mapping of a chain definition (US-08.1, issue #33) onto the
 * {@code chains} table of schema V1. Infrastructure-only: the domain
 * sees the {@link es.jllopez.neis.domain.model.Chain} snapshot through
 * the chain-store connector. Timestamps are ISO-8601 TEXT per the
 * schema's SQLite storage format.
 */
@Entity
@Table(name = "chains")
public class ChainEntity {

    @Id
    private String id;

    @Column(nullable = false, unique = true)
    private String name;

    private String description;

    /** ISO-8601 TEXT (the schema's storage format for SQLite). */
    @Column(name = "created_at", nullable = false)
    private String createdAt;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(String createdAt) {
        this.createdAt = createdAt;
    }

    /** Convenience for the connector: the domain instant. */
    public Instant createdAtInstant() {
        return createdAt == null ? null : Instant.parse(createdAt);
    }
}
