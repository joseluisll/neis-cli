package es.jllopez.neis.infrastructure.persistence;

import es.jllopez.neis.domain.model.AddressType;
import es.jllopez.neis.domain.model.Capture;
import es.jllopez.neis.domain.model.ContentRecord;
import es.jllopez.neis.domain.port.ContentStorePort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The content tree persists (US-11, epic #11): captures round-trip on
 * their composite key (US-11.4), content records round-trip with their
 * deterministic ids (US-11.2), re-runs update instead of duplicate,
 * and the address FKs are enforced.
 */
@SpringBootTest
class ContentStorePersistenceTest {

    @TempDir
    static Path tempDir;

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> "jdbc:sqlite:" + tempDir.resolve("content.db"));
    }

    @Autowired
    private ContentStorePort store;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private void seedAddress(String id) {
        jdbcTemplate.update(
                "INSERT OR IGNORE INTO addresses (id, name, url, type, verification_status, "
                        + "created_at, updated_at) VALUES (?, 'Fixture', 'https://example.org/x', "
                        + "'URL_NEWS', 'UNVERIFIED', '2026-11-11T00:00:00Z', '2026-11-11T00:00:00Z')",
                id);
    }

    @Test
    void captureRoundTripsOnItsCompositeKeyAndUpserts() {
        seedAddress("ADDR-C1");
        Capture capture = new Capture("ADDR-C1", "cap-abc123", "https://example.org/listing",
                Instant.parse("2026-11-11T10:00:00Z"));
        store.saveCapture(capture);

        Capture reloaded = store.findCapture("ADDR-C1", "cap-abc123").orElseThrow();
        assertEquals("https://example.org/listing", reloaded.url());
        assertEquals(Instant.parse("2026-11-11T10:00:00Z"), reloaded.createdAt());

        // Same capture again (a re-run): updates, no duplicate.
        store.saveCapture(new Capture("ADDR-C1", "cap-abc123", "https://example.org/listing",
                Instant.parse("2026-11-11T11:00:00Z")));
        assertEquals(1, store.findCapture("ADDR-C1", "cap-abc123").stream().count());
        assertTrue(store.findCapture("ADDR-C1", "cap-other").isEmpty());
    }

    @Test
    void contentRecordRoundTripsAndUpdatesOnRerun() {
        seedAddress("ADDR-C2");
        store.saveCapture(new Capture("ADDR-C2", "cap-def456", "https://example.org/x",
                Instant.parse("2026-11-11T10:00:00Z")));
        ContentRecord record = new ContentRecord("CONT-1", "ADDR-C2", "cap-def456",
                "WEBSCRAPE", "listing-html", "/work/ADDR-C2/cap-def456/content/WEBSCRAPE/scrape.html",
                Instant.parse("2026-11-11T10:01:00Z"));
        store.saveContent(record);

        ContentRecord reloaded = store.findByAddress("ADDR-C2").get(0);
        assertEquals("WEBSCRAPE", reloaded.taskCode());
        assertEquals("listing-html", reloaded.kind());

        store.saveContent(new ContentRecord("CONT-1", "ADDR-C2", "cap-def456",
                "WEBSCRAPE", "listing-html", "/work/ADDR-C2/cap-def456/content/WEBSCRAPE/scrape.html",
                Instant.parse("2026-11-11T10:05:00Z")));
        assertEquals(1, store.findByAddress("ADDR-C2").size(),
                "the deterministic id makes a re-run an UPDATE, not a duplicate");
        assertEquals(1, store.findAll().size());
    }

    @Test
    void aCaptureForAnUnknownAddressIsRefusedByTheForeignKey() {
        assertThrows(RuntimeException.class,
                () -> store.saveCapture(new Capture("ADDR-MISSING", "cap-xyz",
                        "https://example.org/y", Instant.now())));
    }
}
