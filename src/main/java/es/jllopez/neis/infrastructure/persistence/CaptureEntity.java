package es.jllopez.neis.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

/**
 * JPA mapping of a webpage capture (US-11.4, issue #69) onto the
 * {@code captures} table of schema V2, with the composite key
 * (address_id, capture_id). Infrastructure-only: the domain sees the
 * {@link es.jllopez.neis.domain.model.Capture} snapshot through the
 * content-store connector. Timestamps are ISO-8601 TEXT per the
 * schema's SQLite storage format.
 */
@Entity
@Table(name = "captures")
@IdClass(CaptureEntity.Key.class)
public class CaptureEntity {

    @Id
    @Column(name = "address_id", nullable = false)
    private String addressId;

    @Id
    @Column(name = "capture_id", nullable = false)
    private String captureId;

    @Column(nullable = false)
    private String url;

    /** ISO-8601 TEXT (the schema's storage format for SQLite). */
    @Column(name = "created_at", nullable = false)
    private String createdAt;

    /** The webpage type the capture was recorded under (US-06.5). */
    private String webpageType;

    /** The applied filter set of the capture, JSON object (US-06.6). */
    @Column(name = "filters")
    private String filters;

    /** The obstruction-clearing record of the capture, JSON object (US-06.7). */
    @Column(name = "cleared")
    private String cleared;

    /** The DOM-analysis change digest of the capture (US-06.9). */
    @Column(name = "dom_digest")
    private String domDigest;

    /** The SIMPLIFY side table of the capture (US-07.6, V17). */
    @Column(name = "simplify_side_table")
    private String sideTable;

    public String getSideTable() {
        return sideTable;
    }

    public void setSideTable(String sideTable) {
        this.sideTable = sideTable;
    }

    public String getDomDigest() {
        return domDigest;
    }

    public void setDomDigest(String domDigest) {
        this.domDigest = domDigest;
    }

    public String getCleared() {
        return cleared;
    }

    public void setCleared(String cleared) {
        this.cleared = cleared;
    }

    public String getFilters() {
        return filters;
    }

    public void setFilters(String filters) {
        this.filters = filters;
    }

    public String getWebpageType() {
        return webpageType;
    }

    public void setWebpageType(String webpageType) {
        this.webpageType = webpageType;
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

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
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

    /** Composite primary key (address_id, capture_id). */
    public static class Key implements Serializable {

        private String addressId;
        private String captureId;

        public Key() {
        }

        public Key(String addressId, String captureId) {
            this.addressId = addressId;
            this.captureId = captureId;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Key key)) {
                return false;
            }
            return Objects.equals(addressId, key.addressId)
                    && Objects.equals(captureId, key.captureId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(addressId, captureId);
        }
    }
}
