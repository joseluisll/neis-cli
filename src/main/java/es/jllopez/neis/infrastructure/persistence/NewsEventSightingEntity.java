package es.jllopez.neis.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * JPA mapping of one sighting of a stored news/event item (US-14.5,
 * issue #83) onto the {@code news_event_sightings} table of schema V6:
 * a record of a capture in which the item appeared, with the US-14.2
 * provenance of that sighting. One row per (item, saveAll) — re-running
 * a chain adds sightings, not records. Timestamps are ISO-8601 TEXT
 * per the schema's SQLite storage format.
 */
@Entity
@Table(name = "news_event_sightings")
public class NewsEventSightingEntity {

    @Id
    private String id;

    /** The stored item this sighting belongs to. */
    @Column(name = "item_id", nullable = false)
    private String itemId;

    /** The address whose capture produced the sighting (an item may
     * also be sighted from other addresses — same canonical id). */
    @Column(name = "address_id", nullable = false)
    private String addressId;

    /** The scrape.html content record of the capture (US-14.2). */
    @Column(name = "content_id")
    private String contentId;

    /** The execution that extracted the item in this sighting. */
    @Column(name = "execution_id")
    private String executionId;

    /** ISO-8601 TEXT (the schema's storage format for SQLite). */
    @Column(name = "seen_at", nullable = false)
    private String seenAt;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getItemId() {
        return itemId;
    }

    public void setItemId(String itemId) {
        this.itemId = itemId;
    }

    public String getAddressId() {
        return addressId;
    }

    public void setAddressId(String addressId) {
        this.addressId = addressId;
    }

    public String getContentId() {
        return contentId;
    }

    public void setContentId(String contentId) {
        this.contentId = contentId;
    }

    public String getExecutionId() {
        return executionId;
    }

    public void setExecutionId(String executionId) {
        this.executionId = executionId;
    }

    public String getSeenAt() {
        return seenAt;
    }

    public void setSeenAt(String seenAt) {
        this.seenAt = seenAt;
    }
}
