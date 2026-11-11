package es.jllopez.neis.infrastructure.persistence;

import es.jllopez.neis.domain.model.AddressCapability;
import es.jllopez.neis.domain.model.AddressType;
import es.jllopez.neis.domain.model.VerificationStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Schema round-trip test (US-02.2, issue #16): the V1 migration creates
 * the schema and the ORM maps entities onto it — write, read back, and
 * verify, through the Spring Data repositories.
 *
 * <p>Runs against a temporary SQLite file so the migrated schema is
 * visible to every pooled connection (an in-memory database would give
 * each connection its own empty database).</p>
 */
@SpringBootTest
class SchemaRoundTripTest {

    @TempDir
    static Path tempDir;

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> "jdbc:sqlite:" + tempDir.resolve("schema-round-trip.db"));
    }

    @Autowired
    private AddressJpaRepository addresses;

    @Autowired
    private SelectorJpaRepository selectors;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void schemaV1IsAppliedByMigrations() {
        var applied = jdbcTemplate.queryForList(
                "SELECT version, success FROM flyway_schema_history ORDER BY installed_rank");
        assertEquals(1, applied.size(), "the V1 schema migration should be the only one applied");
        var row = (Map<String, Object>) applied.get(0);
        assertEquals("1", String.valueOf(row.get("version")));
        // SQLite stores booleans as integers: 1 = success.
        assertEquals(1, ((Number) row.get("success")).intValue());
    }

    @Test
    void addressRoundTripsWithCapabilities() {
        AddressEntity address = new AddressEntity();
        address.setId("ADDR-TEST");
        address.setName("Test news listing");
        address.setDescription("Round-trip fixture");
        address.setUrl("https://example.org/news");
        address.setType(AddressType.URL_NEWS);
        address.setCreatedAt(Instant.now().toString());
        address.setUpdatedAt(Instant.now().toString());
        address.setCapabilities(Set.of(AddressCapability.PAGINATION));

        addresses.save(address);

        // A fresh read from the database (no shared first-level cache:
        // each repository call runs in its own transaction).
        AddressEntity reloaded = addresses.findById("ADDR-TEST").orElseThrow();
        assertEquals("https://example.org/news", reloaded.getUrl());
        assertEquals(AddressType.URL_NEWS, reloaded.getType());
        assertEquals(VerificationStatus.UNVERIFIED, reloaded.getVerificationStatus());
        assertEquals(Set.of(AddressCapability.PAGINATION), reloaded.getCapabilities());
    }

    @Test
    void selectorRoundTrips() {
        AddressEntity address = new AddressEntity();
        address.setId("ADDR-TEST-SEL");
        address.setName("Test listing");
        address.setUrl("https://example.org/list");
        address.setType(AddressType.URL_EVENTS);
        address.setCreatedAt(Instant.now().toString());
        address.setUpdatedAt(Instant.now().toString());
        addresses.save(address);

        SelectorEntity selector = new SelectorEntity();
        selector.setAddressId("ADDR-TEST-SEL");
        selector.setKind("news");
        selector.setSelector("ul.items > li");
        selector.setDiscoveredBy("ai");
        selector.setDiscoveredAt("2026-11-11");
        selector.setNotes("Round-trip fixture");
        selectors.save(selector);

        SelectorEntity reloaded = selectors.findById("ADDR-TEST-SEL").orElseThrow();
        assertEquals("ul.items > li", reloaded.getSelector());
        assertEquals("ai", reloaded.getDiscoveredBy());
        assertTrue(addresses.existsById("ADDR-TEST-SEL"),
                "the selector's address must exist (written via the FK-linked path)");
    }

    @Test
    void foreignKeysAreEnforced() {
        // sqlite-jdbc defaults PRAGMA foreign_keys to OFF; the Hikari
        // connection-init-sql turns it on for every pooled connection.
        // An orphan row must be rejected by the FK constraint itself,
        // not silently accepted and not rejected for another reason.
        var rejected = org.junit.jupiter.api.Assertions.assertThrows(
                org.springframework.jdbc.UncategorizedSQLException.class,
                () -> jdbcTemplate.update(
                        "INSERT INTO selectors (address_id, kind, selector, discovered_by, discovered_at) "
                                + "VALUES ('ADDR-DOES-NOT-EXIST', 'news', 'x', 'test', '2026-11-11')"));
        org.junit.jupiter.api.Assertions.assertTrue(
                String.valueOf(rejected.getMessage()).contains("FOREIGN KEY"),
                "the orphan insert must be rejected by the FK constraint, was: "
                        + rejected.getMessage());
    }
}
