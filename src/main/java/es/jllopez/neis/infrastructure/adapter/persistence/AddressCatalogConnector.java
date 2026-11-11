package es.jllopez.neis.infrastructure.adapter.persistence;

import es.jllopez.neis.domain.model.Address;
import es.jllopez.neis.domain.port.AddressCatalogPort;
import es.jllopez.neis.infrastructure.persistence.AddressEntity;
import es.jllopez.neis.infrastructure.persistence.AddressJpaRepository;
import es.jllopez.neis.infrastructure.persistence.SelectorJpaRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Driven adapter implementing {@link AddressCatalogPort} over the
 * Spring Data JPA repository (US-03.1/#18, US-03.3/#20): the address
 * catalog lives in the SQLite {@code addresses} table of schema V1 and
 * survives application restarts.
 *
 * <p>The domain {@link Address} record stays pure (hexagonal rule):
 * this connector owns the mapping to {@link AddressEntity}. The
 * verification columns (US-04) are owned by the verification flow and
 * are preserved untouched by catalog saves.</p>
 */
@Component
public class AddressCatalogConnector implements AddressCatalogPort {

    private final AddressJpaRepository jpa;
    private final SelectorJpaRepository selectorJpa;

    public AddressCatalogConnector(AddressJpaRepository jpa,
                                   SelectorJpaRepository selectorJpa) {
        this.jpa = jpa;
        this.selectorJpa = selectorJpa;
    }

    @Override
    @Transactional
    public Address save(Address address) {
        AddressEntity entity = jpa.findById(address.id()).orElseGet(AddressEntity::new);
        boolean isNew = entity.getId() == null;
        if (isNew) {
            entity.setId(address.id());
            entity.setVerificationStatus("UNVERIFIED");
            entity.setCreatedAt(now());
        }
        entity.setName(address.name());
        entity.setDescription(address.description());
        entity.setUrl(address.url());
        entity.setType(address.type());
        entity.setCapabilities(address.capabilities());
        entity.setUpdatedAt(now());
        return toDomain(jpa.save(entity));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Address> findById(String id) {
        return jpa.findById(id).map(this::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Address> findAll() {
        return jpa.findAll().stream().map(this::toDomain).toList();
    }

    @Override
    @Transactional
    public boolean deleteById(String id) {
        if (!jpa.existsById(id)) {
            return false;
        }
        // The stored working selector belongs to the address: it goes
        // with it. Execution and content records are history and are
        // kept — the FK constraint refuses the delete instead.
        selectorJpa.deleteById(id);
        selectorJpa.flush();
        try {
            jpa.deleteById(id);
            jpa.flush();
        } catch (DataIntegrityViolationException e) {
            throw new IllegalStateException(
                    "Address " + id + " cannot be removed: execution or content "
                            + "records still reference it", e);
        }
        return true;
    }

    private Address toDomain(AddressEntity e) {
        return new Address(e.getId(), e.getName(), e.getDescription(), e.getUrl(),
                e.getType(), e.getCapabilities());
    }

    private String now() {
        return Instant.now().toString();
    }
}
