package es.jllopez.neis.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * JPA mapping of a stored news/event item (US-14.1, issue #55) onto the
 * {@code news_events} table of schema V3. Infrastructure-only: the
 * domain sees the {@link es.jllopez.neis.domain.model.NewsEventItem}
 * snapshot through the news-event store connector. Timestamps are
 * ISO-8601 TEXT per the schema's SQLite storage format.
 */
@Entity
@Table(name = "news_events")
public class NewsEventEntity {

    @Id
    private String id;

    @Column(name = "address_id", nullable = false)
    private String addressId;

    /** "news" or "event" — the model distinguishes the two kinds. */
    @Column(nullable = false)
    private String kind;

    @Column(nullable = false)
    private String title;

    @Column
    private String description;

    @Column
    private String date;

    /** The normalized ISO-8601 date (US-14.9, issue #123) — the raw
     * text through the shared domain normalizer; null when the raw
     * date is missing or unparseable. */
    @Column(name = "date_normalized")
    private String dateNormalized;

    @Column
    private String url;

    /** ISO-8601 TEXT (the schema's storage format for SQLite). */
    @Column(name = "created_at", nullable = false)
    private String createdAt;

    @Column(name = "updated_at", nullable = false)
    private String updatedAt;

    /** The execution that extracted this item (US-14.2 provenance). */
    @Column(name = "execution_id")
    private String executionId;

    /** The scrape.html content record the item came from (US-14.2). */
    @Column(name = "content_id")
    private String contentId;

    /** Optional picture URL (US-14.4). */
    @Column
    private String picture;

    /** Optional location/venue (US-14.4). */
    @Column
    private String location;

    /** True when a mandatory field is missing (US-14.4). SQLite
     * stores booleans as integers per the schema's storage format. */
    @Column(nullable = false)
    private boolean incomplete;

    /** The missing mandatory fields, comma-separated (US-14.4). */
    @Column(name = "missing_fields")
    private String missingFields;

    /** The deterministic dedup id (US-14.5): rows sharing it are one
     * logical record. Nullable (rows persisted before V6). */
    @Column(name = "canonical_id")
    private String canonicalId;

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

    public String getKind() {
        return kind;
    }

    public void setKind(String kind) {
        this.kind = kind;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getDate() {
        return date;
    }

    public void setDate(String date) {
        this.date = date;
    }

    public String getDateNormalized() {
        return dateNormalized;
    }

    public void setDateNormalized(String dateNormalized) {
        this.dateNormalized = dateNormalized;
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

    public String getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(String updatedAt) {
        this.updatedAt = updatedAt;
    }

    public String getExecutionId() {
        return executionId;
    }

    public void setExecutionId(String executionId) {
        this.executionId = executionId;
    }

    public String getContentId() {
        return contentId;
    }

    public void setContentId(String contentId) {
        this.contentId = contentId;
    }

    public String getPicture() {
        return picture;
    }

    public void setPicture(String picture) {
        this.picture = picture;
    }

    public String getLocation() {
        return location;
    }

    public void setLocation(String location) {
        this.location = location;
    }

    public boolean isIncomplete() {
        return incomplete;
    }

    public void setIncomplete(boolean incomplete) {
        this.incomplete = incomplete;
    }

    public String getMissingFields() {
        return missingFields;
    }

    public void setMissingFields(String missingFields) {
        this.missingFields = missingFields;
    }

    public String getCanonicalId() {
        return canonicalId;
    }

    public void setCanonicalId(String canonicalId) {
        this.canonicalId = canonicalId;
    }
}
