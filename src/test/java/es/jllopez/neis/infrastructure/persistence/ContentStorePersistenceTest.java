package es.jllopez.neis.infrastructure.persistence;

import com.zaxxer.hikari.HikariDataSource;
import es.jllopez.neis.domain.model.AddressType;
import es.jllopez.neis.domain.model.Capture;
import es.jllopez.neis.domain.model.ContentRecord;
import es.jllopez.neis.domain.port.ContentStorePort;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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

    /**
     * US-02.4 (issue #98): the Hikari pool holds the SQLite file open
     * after the test class, so on Windows JUnit's @TempDir cleanup
     * cannot delete it and the extension teardown errors (invisible on
     * Linux, where unlink-while-open succeeds). Closing the pool here
     * releases the file BEFORE the @TempDir deletion — JUnit runs
     * @AfterAll methods before extension cleanup. The context is
     * per-class (its datasource URL points at this class's temp dir),
     * so closing the pool disturbs no other test class.
     */
    @AfterAll
    static void releaseDatabaseFile(@Autowired DataSource dataSource) {
        if (dataSource instanceof HikariDataSource pool && !pool.isClosed()) {
            pool.close();
        }
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
    void captureFiltersRoundTripAndAFilterlessCaptureNeverWipesThem() {
        // US-06.6 (issue #73) / US-11.4: the applied filter set is part
        // of the capture metadata; a later filter-less re-capture never
        // wipes it (the webpage-type rule).
        seedAddress("ADDR-CF");
        store.saveCapture(new Capture("ADDR-CF", "cap-f1lt3r5", "https://example.org/filtered",
                Instant.parse("2026-11-11T10:00:00Z")));
        assertTrue(store.findCapture("ADDR-CF", "cap-f1lt3r5").orElseThrow()
                .filters() == null, "no filters recorded yet");

        store.saveCapture(new Capture("ADDR-CF", "cap-f1lt3r5", "https://example.org/filtered",
                Instant.parse("2026-11-11T10:00:00Z"), null,
                "{\"programme\":\"EU4Health\",\"text\":\"wallet\"}"));
        Capture filtered = store.findCapture("ADDR-CF", "cap-f1lt3r5").orElseThrow();
        assertEquals("{\"programme\":\"EU4Health\",\"text\":\"wallet\"}", filtered.filters(),
                "the applied filter set round-trips as stored");

        // A filter-less re-capture (e.g. an unfiltered SIMPLIFY over the
        // same capture) never wipes the set.
        store.saveCapture(new Capture("ADDR-CF", "cap-f1lt3r5", "https://example.org/filtered",
                Instant.parse("2026-11-11T10:00:00Z")));
        assertEquals("{\"programme\":\"EU4Health\",\"text\":\"wallet\"}",
                store.findCapture("ADDR-CF", "cap-f1lt3r5").orElseThrow().filters(),
                "a null filter set never wipes a stored one");
    }

    @Test
    void contentRecordRoundTripsAndUpdatesOnRerun() {
        seedAddress("ADDR-C2");
        store.saveCapture(new Capture("ADDR-C2", "cap-def456", "https://example.org/x",
                Instant.parse("2026-11-11T10:00:00Z")));
        ContentRecord record = new ContentRecord("CONT-1", "ADDR-C2", "cap-def456",
                "WEBSCRAPE", "listing-html", "/work/ADDR-C2/cap-def456/content/WEBSCRAPE/scrape.html",
                Instant.parse("2026-11-11T10:01:00Z"), null);
        store.saveContent(record);

        ContentRecord reloaded = store.findByAddress("ADDR-C2").get(0);
        assertEquals("WEBSCRAPE", reloaded.taskCode());
        assertEquals("listing-html", reloaded.kind());

        store.saveContent(new ContentRecord("CONT-1", "ADDR-C2", "cap-def456",
                "WEBSCRAPE", "listing-html", "/work/ADDR-C2/cap-def456/content/WEBSCRAPE/scrape.html",
                Instant.parse("2026-11-11T10:05:00Z"), null));
        assertEquals(1, store.findByAddress("ADDR-C2").size(),
                "the deterministic id makes a re-run an UPDATE, not a duplicate");
        // findAll is store-global (the class shares one SQLite file
        // across its tests): assert THIS record exactly once, not the
        // whole store's size.
        assertEquals(1, store.findAll().stream()
                .filter(r -> "CONT-1".equals(r.id())).count());
    }

    @Test
    void executionAttributionRoundTripsAndFollowsTheLatestRun() {
        // US-16.6 (issue #102): a record written by a run carries that
        // run's execution id; a re-run by ANOTHER run UPDATES the
        // attribution (the deterministic id makes it the same row), and
        // an unattributed (pre-existing) record stays null.
        seedAddress("ADDR-C3");
        seedAddress("ADDR-C4");
        store.saveCapture(new Capture("ADDR-C3", "cap-exec111", "https://example.org/e",
                Instant.parse("2026-11-16T10:00:00Z")));
        store.saveCapture(new Capture("ADDR-C4", "cap-pre222", "https://example.org/p",
                Instant.parse("2026-11-16T10:00:00Z")));

        store.saveContent(new ContentRecord("CONT-att", "ADDR-C3", "cap-exec111",
                "WEBSCRAPE", "listing-html", "/work/C3/scrape.html",
                Instant.parse("2026-11-16T10:01:00Z"), "EXEC-1"));
        assertEquals("EXEC-1", store.findById("CONT-att").orElseThrow().executionId(),
                "the run's id is stored with the record");

        // Re-run by another execution: the SAME row, new attribution.
        store.saveContent(new ContentRecord("CONT-att", "ADDR-C3", "cap-exec111",
                "WEBSCRAPE", "listing-html", "/work/C3/scrape.html",
                Instant.parse("2026-11-16T10:05:00Z"), "EXEC-2"));
        assertEquals(1, store.findByAddress("ADDR-C3").size());
        assertEquals("EXEC-2", store.findByAddress("ADDR-C3").get(0).executionId(),
                "the attribution follows the latest producing run");

        // An unattributed record round-trips as null.
        store.saveContent(new ContentRecord("CONT-pre", "ADDR-C4", "cap-pre222",
                "SIMPLIFY", "simplified-html", "/work/C4/simplified.html",
                Instant.parse("2026-11-16T10:02:00Z"), null));
        assertNull(store.findById("CONT-pre").orElseThrow().executionId(),
                "pre-existing records stay address-scoped (unattributed)");
    }

    @Test
    void aCaptureForAnUnknownAddressIsRefusedByTheForeignKey() {
        assertThrows(RuntimeException.class,
                () -> store.saveCapture(new Capture("ADDR-MISSING", "cap-xyz",
                        "https://example.org/y", Instant.now())));
    }
}
