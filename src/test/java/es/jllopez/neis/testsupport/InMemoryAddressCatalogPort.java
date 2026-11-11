package es.jllopez.neis.testsupport;

import es.jllopez.neis.domain.model.Address;
import es.jllopez.neis.domain.port.AddressCatalogPort;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Test double of {@link AddressCatalogPort}: an in-memory map, ordered
 * by insertion. Stands in for the SQLite-backed connector in
 * application/domain unit tests (the real connector is exercised by the
 * persistence tests and the CLI run).
 */
public class InMemoryAddressCatalogPort implements AddressCatalogPort {

    private final Map<String, Address> addresses = new LinkedHashMap<>();

    public InMemoryAddressCatalogPort() {
    }

    public InMemoryAddressCatalogPort(List<Address> seed) {
        seed.forEach(this::save);
    }

    @Override
    public Address save(Address address) {
        addresses.put(address.id(), address);
        return address;
    }

    @Override
    public Optional<Address> findById(String id) {
        return Optional.ofNullable(addresses.get(id));
    }

    @Override
    public List<Address> findAll() {
        // The port contract orders by id; the JPA connector does the
        // same via its query sort.
        return addresses.values().stream()
                .sorted(java.util.Comparator.comparing(Address::id,
                        String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    @Override
    public boolean deleteById(String id) {
        return addresses.remove(id) != null;
    }
}
