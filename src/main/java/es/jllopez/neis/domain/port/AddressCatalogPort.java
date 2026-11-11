package es.jllopez.neis.domain.port;

import es.jllopez.neis.domain.model.Address;

import java.util.List;
import java.util.Optional;

/**
 * Repository port for the registered addresses (US-03.1, issue #18).
 * The MVP's in-memory seed catalog was replaced by a database-backed
 * implementation behind this port (US-03.3, issue #20): the application
 * reaches persistence only through here, so the store stays swappable.
 */
public interface AddressCatalogPort {

    /** Inserts or updates an address, keyed by its id. */
    Address save(Address address);

    /** The registered address with the given id, if present. */
    Optional<Address> findById(String id);

    /** Every registered address, ordered by id. */
    List<Address> findAll();

    /**
     * Removes the address with the given id. Returns false when no
     * such address exists; throws when the address is still referenced
     * by other records (executions, stored content).
     */
    boolean deleteById(String id);
}
