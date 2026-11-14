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

    @Column
    private String url;

    /** ISO-8601 TEXT (the schema's storage format for SQLite). */
    @Column(name = "created_at", nullable = false)
    private String createdAt;

    @Column(name = "updated_at", nullable = false)
    private String updatedAt;

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
}
