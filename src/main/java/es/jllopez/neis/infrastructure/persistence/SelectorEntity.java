package es.jllopez.neis.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * JPA mapping of a stored working selector (MVP issue #90) onto the
 * {@code selectors} table of schema V1. Field-for-field mirror of the
 * domain {@link es.jllopez.neis.domain.model.StoredSelectors} record;
 * the DB-backed selector-store connector maps between them.
 */
@Entity
@Table(name = "selectors")
public class SelectorEntity {

    @Id
    private String addressId;

    @Column(nullable = false)
    private String kind;

    @Column(nullable = false)
    private String selector;

    private String filterSelector;

    @Column(nullable = false)
    private String discoveredBy;

    @Column(nullable = false)
    private String discoveredAt;

    private String notes;

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

    public String getSelector() {
        return selector;
    }

    public void setSelector(String selector) {
        this.selector = selector;
    }

    public String getFilterSelector() {
        return filterSelector;
    }

    public void setFilterSelector(String filterSelector) {
        this.filterSelector = filterSelector;
    }

    public String getDiscoveredBy() {
        return discoveredBy;
    }

    public void setDiscoveredBy(String discoveredBy) {
        this.discoveredBy = discoveredBy;
    }

    public String getDiscoveredAt() {
        return discoveredAt;
    }

    public void setDiscoveredAt(String discoveredAt) {
        this.discoveredAt = discoveredAt;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }
}
