package es.jllopez.neis.application;

import es.jllopez.neis.domain.model.Address;
import es.jllopez.neis.domain.model.AddressCapability;
import es.jllopez.neis.domain.model.AddressType;
import es.jllopez.neis.domain.model.VerificationStatus;
import es.jllopez.neis.domain.port.AddressCatalogPort;
import es.jllopez.neis.domain.service.UrlSyntaxValidator;
import org.springframework.stereotype.Service;

import java.util.HashSet;
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
    private final UrlSyntaxValidator urlValidator;

    public AddressCatalogService(AddressCatalogPort catalogPort,
                                 UrlSyntaxValidator urlValidator) {
        this.catalogPort = catalogPort;
        this.urlValidator = urlValidator;
    }

    /** Every registered address, ordered by id. */
    public List<Address> list() {
        return catalogPort.findAll();
    }

    /** The registered address with the given id (case-insensitive). */
    public Address byId(String id) {
        List<Address> all = catalogPort.findAll();
        return catalogPort.findById(id)
                .or(() -> all.stream()
                        .filter(a -> a.id().equalsIgnoreCase(id))
                        .findFirst())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown address id: " + id
                                + " (registered addresses: "
                                + (all.isEmpty()
                                ? "<catalog is empty — add one with 'address add'>"
                                : all.stream().map(Address::id).toList()) + ")"));
    }

    /**
     * Registers a new address (US-03.2, issue #19). When no id is
     * given, the next free {@code ADDR-NNN} id is assigned.
     *
     * <p>Idempotent (US-15.4, issue #70): re-registering an address
     * whose registered state already matches is a no-op returning the
     * existing record; a re-registration with different fields updates
     * the registered state (same end state as a single run), keeping
     * the verification state and capability flags.</p>
     */
    public Address create(String id, String name, String description,
                          String url, AddressType type) {
        requireValidUrl(url);
        String addressId = (id == null || id.isBlank())
                ? nextAddressId().orElseThrow()
                : id;
        Address existing = catalogPort.findAll().stream()
                .filter(a -> a.id().equalsIgnoreCase(addressId))
                .findFirst()
                .orElse(catalogPort.findById(addressId).orElse(null));
        if (existing != null) {
            boolean sameState = existing.name().equals(name)
                    && java.util.Objects.equals(existing.description(), description)
                    && existing.url().equals(url)
                    && existing.type() == type;
            if (sameState) {
                return existing; // no-op: the state already matches (US-15.4)
            }
            return update(existing.id(), name, description, url, type);
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
        requireValidUrl(url); // null keeps the current (validated) URL
        Address current = byId(id);
        // Preserve the loaded verification state (US-04.2): catalog
        // edits never touch it — only 'address verify' does.
        return catalogPort.save(new Address(current.id(),
                name != null ? name : current.name(),
                description != null ? description : current.description(),
                url != null ? url : current.url(),
                type != null ? type : current.type(),
                current.capabilities(),
                current.verificationStatus(), current.verifiedAt()));
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
                .mapToInt(id -> {
                    // A user-supplied id may exceed int range: bound it
                    // instead of letting the parse throw on every call.
                    try {
                        return Integer.parseInt(id.substring(5));
                    } catch (NumberFormatException e) {
                        return 0;
                    }
                })
                .max()
                .orElse(0);
        return Optional.of(String.format("ADDR-%03d", max + 1));
    }

    /**
     * Records a verification outcome onto the address (US-04.2, issue
     * #22): the only path that changes the verification columns.
     */
    public Address updateVerification(String id, VerificationStatus status,
                                      String checkedAt) {
        return catalogPort.save(byId(id).withVerification(status, checkedAt));
    }

    /**
     * Records the capabilities ANALYZE detected onto the address
     * (US-17, deferred slice-3 review finding): the only path that
     * changes the capability flags. Detections MERGE with the stored
     * flags — a detection run with weaker evidence (generic fallbacks
     * when no AI is configured) must never erase a flag an earlier
     * run established; only an owner action could retire a flag.
     */
    public Address updateCapabilities(String id, Set<AddressCapability> detected) {
        Address current = byId(id);
        Set<AddressCapability> merged = new HashSet<>(current.capabilities());
        merged.addAll(detected == null ? Set.of() : detected);
        return catalogPort.save(current.withCapabilities(merged));
    }

    /**
     * Syntactic URL validation at creation/edit time (US-04.1, issue
     * #21): no address enters the catalog with a malformed URL.
     */
    private void requireValidUrl(String url) {
        if (url == null) {
            return; // update: null keeps the current URL
        }
        urlValidator.validate(url).ifPresent(reason -> {
            throw new IllegalArgumentException(
                    "Invalid address URL — " + reason);
        });
    }
}
