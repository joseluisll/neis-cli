package es.jllopez.neis.application;

import es.jllopez.neis.domain.model.Address;
import es.jllopez.neis.domain.port.AddressCatalogPort;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Use case: the registered address catalog (US-03, epic #3).
 *
 * <p>MVP history: the catalog was the in-memory seed list of issue #62.
 * Slice 2 of the platform (US-03.1/#18) replaces it with the
 * database-backed implementation behind {@link AddressCatalogPort} —
 * same use-case surface, persistent store (US-03.3/#20). CRUD CLI
 * commands arrive with US-03.2 (#19).</p>
 */
@Service
public class AddressCatalogService {

    private final AddressCatalogPort catalogPort;

    public AddressCatalogService(AddressCatalogPort catalogPort) {
        this.catalogPort = catalogPort;
    }

    /** Every registered address, ordered by id. */
    public List<Address> list() {
        return catalogPort.findAll();
    }

    /** The registered address with the given id. */
    public Address byId(String id) {
        return catalogPort.findById(id)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown address id: " + id
                                + " (registered addresses: "
                                + (catalogPort.findAll().isEmpty()
                                ? "<catalog is empty — add one with 'address add'>"
                                : catalogPort.findAll().stream()
                                .map(Address::id).toList()) + ")"));
    }
}
