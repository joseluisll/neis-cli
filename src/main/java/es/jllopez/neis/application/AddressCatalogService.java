package es.jllopez.neis.application;

import es.jllopez.neis.domain.model.Address;
import es.jllopez.neis.domain.model.AddressType;
import es.jllopez.neis.domain.port.AddressCatalogPort;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Use case: the registered address catalog (US-03, epic #3).
 *
 * <p>MVP history: the catalog was the in-memory seed list of issue #62.
 * Slice 2 of the platform (US-03.1/#18) replaces it with the
 * database-backed implementation behind {@link AddressCatalogPort} —
 * same use-case surface, persistent store (US-03.3/#20). The CRUD
 * operations back the CLI commands of US-03.2 (#19).</p>
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

    /** The registered address with the given id (case-insensitive). */
    public Address byId(String id) {
        return catalogPort.findById(id)
                .or(() -> catalogPort.findAll().stream()
                        .filter(a -> a.id().equalsIgnoreCase(id))
                        .findFirst())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown address id: " + id
                                + " (registered addresses: "
                                + (catalogPort.findAll().isEmpty()
                                ? "<catalog is empty — add one with 'address add'>"
                                : catalogPort.findAll().stream()
                                .map(Address::id).toList()) + ")"));
    }

    /**
     * Registers a new address (US-03.2, issue #19). When no id is
     * given, the next free {@code ADDR-NNN} id is assigned.
     */
    public Address create(String id, String name, String description,
                          String url, AddressType type) {
        String addressId = (id == null || id.isBlank())
                ? nextAddressId().orElseThrow()
                : id;
        if (catalogPort.findById(addressId).isPresent()
                || catalogPort.findAll().stream()
                        .anyMatch(a -> a.id().equalsIgnoreCase(addressId))) {
            throw new IllegalArgumentException(
                    "Address id already registered: " + addressId);
        }
        return catalogPort.save(new Address(addressId, name, description,
                url, type, Set.of()));
    }

    /**
     * Edits an existing address (US-03.2, issue #19): every null
     * argument keeps the current value. The id and the capability
     * flags are not editable (flags belong to the ANALYZE outcome).
     */
    public Address update(String id, String name, String description,
                          String url, AddressType type) {
        Address current = byId(id);
        return catalogPort.save(new Address(current.id(),
                name != null ? name : current.name(),
                description != null ? description : current.description(),
                url != null ? url : current.url(),
                type != null ? type : current.type(),
                current.capabilities()));
    }

    /**
     * Removes an address (US-03.2, issue #19). Returns the removed
     * address; the stored working selector goes with it, while
     * execution/content history keeps the address referenced (the
     * remove is refused in that case).
     */
    public Address remove(String id) {
        Address address = byId(id);
        catalogPort.deleteById(address.id());
        return address;
    }

    /** The next free {@code ADDR-NNN} id (US-03.2, issue #19). */
    public Optional<String> nextAddressId() {
        int max = catalogPort.findAll().stream()
                .map(Address::id)
                .filter(id -> id.toUpperCase(Locale.ROOT).matches("ADDR-\\d+"))
                .mapToInt(id -> Integer.parseInt(id.substring(5)))
                .max()
                .orElse(0);
        return Optional.of(String.format("ADDR-%03d", max + 1));
    }
}
