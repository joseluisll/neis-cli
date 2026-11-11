package es.jllopez.neis.infrastructure.persistence;

import es.jllopez.neis.domain.model.AddressCapability;
import es.jllopez.neis.domain.model.AddressType;
import es.jllopez.neis.domain.model.VerificationStatus;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;

import java.util.HashSet;
import java.util.Set;

/**
 * JPA mapping of a registered address (US-03, epic #3) onto the
 * {@code addresses} table of schema V1. Infrastructure-only: the domain
 * {@link es.jllopez.neis.domain.model.Address} record stays pure, and the
 * mapping to it happens in the repository connector (repository port).
 *
 * <p>{@code verificationStatus} is a free string until US-04 types it in
 * the domain; the DB CHECK constraint constrains the values. Timestamps
 * are ISO-8601 TEXT (the schema's storage format for SQLite).</p>
 */
@Entity
@Table(name = "addresses")
public class AddressEntity {

    @Id
    private String id;

    @Column(nullable = false)
    private String name;

    private String description;

    @Column(nullable = false)
    private String url;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AddressType type;

    /** Verification state (US-04.2), stored as its enum name. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private VerificationStatus verificationStatus = VerificationStatus.UNVERIFIED;

    private String verifiedAt;

    @Column(nullable = false)
    private String createdAt;

    @Column(nullable = false)
    private String updatedAt;

    /** Capability flags (US-17), stored as their enum names in the join table. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "address_capabilities", joinColumns = @JoinColumn(name = "address_id"))
    @Column(name = "capability")
    @Enumerated(EnumType.STRING)
    private Set<AddressCapability> capabilities = new HashSet<>();

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

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public AddressType getType() {
        return type;
    }

    public void setType(AddressType type) {
        this.type = type;
    }

    public VerificationStatus getVerificationStatus() {
        return verificationStatus;
    }

    public void setVerificationStatus(VerificationStatus verificationStatus) {
        this.verificationStatus = verificationStatus;
    }

    public String getVerifiedAt() {
        return verifiedAt;
    }

    public void setVerifiedAt(String verifiedAt) {
        this.verifiedAt = verifiedAt;
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

    public Set<AddressCapability> getCapabilities() {
        return capabilities;
    }

    public void setCapabilities(Set<AddressCapability> capabilities) {
        this.capabilities = capabilities;
    }
}
