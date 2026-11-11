package es.jllopez.neis.infrastructure.persistence;

import es.jllopez.neis.domain.model.Address;
import es.jllopez.neis.domain.model.AddressType;
import es.jllopez.neis.domain.port.AddressCatalogPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The registered address catalog persists (US-03.3, issue #20): the
 * CRUD operations of US-03.2 run against the SQLite file database
 * behind {@link AddressCatalogPort}, so the catalog survives
 * application restarts. Every read here goes back to the database
 * (each repository call runs in its own transaction/persistence
 * context); the equivalent live-restart proof is the CLI run recorded
 * with commit "Add address CRUD through the CLI (US-03.2)".
 */
@SpringBootTest
class AddressCatalogPersistenceTest {

    @TempDir
    static Path tempDir;

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> "jdbc:sqlite:" + tempDir.resolve("address-catalog.db"));
    }

    @Autowired
    private AddressCatalogPort catalog;

    @Autowired
    private SelectorJpaRepository selectors;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Address address(String id, String url, AddressType type) {
        return new Address(id, "Test " + id, "Persistence fixture " + id,
                url, type, java.util.Set.of());
    }

    @Test
    void savedAddressRoundTripsThroughThePort() {
        catalog.save(address("ADDR-P1", "https://example.org/p1", AddressType.URL_NEWS));

        // Fresh read: no shared first-level cache.
        Address reloaded = catalog.findById("ADDR-P1").orElseThrow();
        assertEquals("Test ADDR-P1", reloaded.name());
        assertEquals("https://example.org/p1", reloaded.url());
        assertEquals(AddressType.URL_NEWS, reloaded.type());
        assertEquals("Persistence fixture ADDR-P1", reloaded.description());
        assertTrue(reloaded.capabilities().isEmpty());
    }

    @Test
    void findAllIsOrderedById() {
        catalog.save(address("ADDR-P3", "https://example.org/p3", AddressType.URL_EVENTS));
        catalog.save(address("ADDR-P2", "https://example.org/p2", AddressType.URL_NEWS));

        // The file database is shared by the tests of this class: assert
        // the relative order of the two rows this test created.
        List<String> ids = catalog.findAll().stream()
                .map(Address::id)
                .filter(id -> id.equals("ADDR-P2") || id.equals("ADDR-P3"))
                .toList();
        assertEquals(List.of("ADDR-P2", "ADDR-P3"), ids);
    }

    @Test
    void updateKeepsVerificationAndCreationTimestamps() {
        catalog.save(address("ADDR-P4", "https://example.org/p4", AddressType.URL_NEWS));
        String createdAt = jdbcTemplate.queryForObject(
                "SELECT created_at FROM addresses WHERE id = 'ADDR-P4'", String.class);

        catalog.save(address("ADDR-P4", "https://example.org/p4-v2", AddressType.URL_NEWS));

        Address updated = catalog.findById("ADDR-P4").orElseThrow();
        assertEquals("https://example.org/p4-v2", updated.url());
        // The catalog CRUD never touches the verification columns: they
        // belong to the verification flow (US-04.2, issue #22).
        assertEquals("UNVERIFIED", jdbcTemplate.queryForObject(
                "SELECT verification_status FROM addresses WHERE id = 'ADDR-P4'", String.class));
        assertEquals(createdAt, jdbcTemplate.queryForObject(
                "SELECT created_at FROM addresses WHERE id = 'ADDR-P4'", String.class));
    }

    @Test
    void deleteTakesTheStoredSelectorWithIt() {
        catalog.save(address("ADDR-P5", "https://example.org/p5", AddressType.URL_EVENTS));
        SelectorEntity selector = new SelectorEntity();
        selector.setAddressId("ADDR-P5");
        selector.setKind("event");
        selector.setSelector("ul.events > li");
        selector.setDiscoveredBy("ai");
        selector.setDiscoveredAt("2026-11-11");
        selectors.save(selector);

        assertTrue(catalog.deleteById("ADDR-P5"));
        assertTrue(catalog.findById("ADDR-P5").isEmpty());
        assertTrue(selectors.findById("ADDR-P5").isEmpty(),
                "the stored working selector goes with the address");
    }

    @Test
    void deleteIsRefusedWhileHistoryReferencesTheAddress() {
        catalog.save(address("ADDR-P6", "https://example.org/p6", AddressType.URL_NEWS));
        jdbcTemplate.update(
                "INSERT INTO executions (id, address_id, trigger_type, status) "
                        + "VALUES ('EXEC-P6', 'ADDR-P6', 'MANUAL', 'SUCCESS')");

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> catalog.deleteById("ADDR-P6"));
        assertTrue(e.getMessage().contains("ADDR-P6"));
        assertTrue(catalog.findById("ADDR-P6").isPresent(),
                "the address stays registered while history references it");

        jdbcTemplate.update("DELETE FROM executions WHERE id = 'EXEC-P6'");
        assertTrue(catalog.deleteById("ADDR-P6"));
    }
}
