package es.jllopez.neis.infrastructure.persistence;

import es.jllopez.neis.domain.model.NewsEventItem;
import es.jllopez.neis.domain.port.NewsEventStorePort;
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
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * The news/events repository persists over a real SQLite file (US-14.1,
 * issue #55): deterministic per-(address, url) ids make re-extraction
 * an upsert — no duplicate rows — and the model distinguishes news from
 * events. Mirrors the other persistence round-trip tests.
 */
@SpringBootTest
class NewsEventStorePersistenceTest {

    @TempDir
    static Path tempDir;

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> "jdbc:sqlite:" + tempDir.resolve("news-events.db"));
    }

    @Autowired
    private NewsEventStorePort store;

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
    void upsertByDeterministicIdDoesNotDuplicate() {
        seedAddress("ADDR-N1");
        var item = new NewsEventItem("ADDR-N1", "news", "Title",
                "Summary", "https://example.eu/news/1", "2026-11-14");

        assertEquals(1, store.saveAll("ADDR-N1", "news", List.of(item), null, null));
        // Re-extraction of the same item: upsert, not a duplicate.
        assertEquals(1, store.saveAll("ADDR-N1", "news", List.of(item), null, null));
        assertEquals(1, store.byAddress("ADDR-N1").size());

        // A changed title for the same URL updates the same row.
        var renamed = new NewsEventItem("ADDR-N1", "news", "Title (updated)",
                "Summary", "https://example.eu/news/1", "2026-11-14");
        store.saveAll("ADDR-N1", "news", List.of(renamed), null, null);
        assertEquals(1, store.byAddress("ADDR-N1").size());
        assertEquals("Title (updated)", store.byAddress("ADDR-N1").get(0).title());
    }

    @Test
    void linkLessItemsAreKeyedOnTitle() {
        seedAddress("ADDR-N2");
        var item = new NewsEventItem("ADDR-N2", "event", "Event without a link",
                null, null, "2026-11-14");

        store.saveAll("ADDR-N2", "event", List.of(item), null, null);
        store.saveAll("ADDR-N2", "event", List.of(item), null, null);

        assertEquals(1, store.byAddress("ADDR-N2").size(),
                "no duplicate rows for link-less items");
        assertEquals("event", store.byAddress("ADDR-N2").get(0).kind());
    }

    @Test
    void createdAtIsPreservedAndUpdatedAtRefreshedOnUpsert() {
        // US-14.1 review finding: the documented upsert semantics —
        // created_at kept, updated_at refreshed — pinned against
        // regression.
        seedAddress("ADDR-N5");
        var item = new NewsEventItem("ADDR-N5", "news", "Timestamped item",
                null, "https://example.eu/news/9", null);
        store.saveAll("ADDR-N5", "news", List.of(item), null, null);
        String createdAt = jdbcTemplate.queryForObject(
                "SELECT created_at FROM news_events WHERE address_id = 'ADDR-N5'",
                String.class);
        String updatedAt = jdbcTemplate.queryForObject(
                "SELECT updated_at FROM news_events WHERE address_id = 'ADDR-N5'",
                String.class);

        store.saveAll("ADDR-N5", "news", List.of(item), null, null);

        assertEquals(createdAt, jdbcTemplate.queryForObject(
                "SELECT created_at FROM news_events WHERE address_id = 'ADDR-N5'",
                String.class), "created_at is preserved on upsert");
        assertNotEquals(updatedAt, jdbcTemplate.queryForObject(
                "SELECT updated_at FROM news_events WHERE address_id = 'ADDR-N5'",
                String.class), "updated_at is refreshed on upsert");
    }

    @Test
    void sameAddressCrossKindUpsertsOneRow() {
        // US-14.1 review finding: the deterministic id excludes kind —
        // the same url under news and event on one address is ONE row
        // whose kind is overwritten by the last save (documented in the
        // V3 header).
        seedAddress("ADDR-N6");
        store.saveAll("ADDR-N6", "news", List.of(new NewsEventItem(
                "ADDR-N6", "news", "Dual-kind item", null,
                "https://example.eu/dual", null)), null, null);
        store.saveAll("ADDR-N6", "event", List.of(new NewsEventItem(
                "ADDR-N6", "event", "Dual-kind item", null,
                "https://example.eu/dual", null)), null, null);

        assertEquals(1, store.byAddress("ADDR-N6").size());
        assertEquals("event", store.byAddress("ADDR-N6").get(0).kind());
    }

    @Test
    void blankUrlsAreNormalizedSoTheUniqueIndexHolds() {
        // US-14.1 review finding: a blank-but-non-null url (raw href
        // fallback) would put two title-keyed items on the same empty
        // index key and violate the unique index — normalized to null.
        seedAddress("ADDR-N7");
        var first = new NewsEventItem("ADDR-N7", "news", "Link-less item",
                null, "  ", null);
        var second = new NewsEventItem("ADDR-N7", "news", "Link-less item",
                null, "", null);

        store.saveAll("ADDR-N7", "news", List.of(first), null, null);
        store.saveAll("ADDR-N7", "news", List.of(second), null, null);

        assertEquals(1, store.byAddress("ADDR-N7").size(),
                "both blank-url variants key on the title: one row");
    }

    @Test
    void provenanceSurvivesTheReadPath() {
        // US-14.2 read path (re-review deferred finding): execution_id /
        // content_id round-trip through byAddress() and all().
        seedAddress("ADDR-N8");
        store.saveAll("ADDR-N8", "news", List.of(new NewsEventItem(
                "ADDR-N8", "news", "Traced item", null,
                "https://example.eu/traced", null)), "exec-42", "CONT-42");

        var byAddress = store.byAddress("ADDR-N8");
        assertEquals(1, byAddress.size());
        assertEquals("exec-42", byAddress.get(0).executionId());
        assertEquals("CONT-42", byAddress.get(0).contentId());

        var all = store.all().stream()
                .filter(item -> "ADDR-N8".equals(item.addressId()))
                .toList();
        assertEquals(1, all.size(),
                "the whole-repository read reaches this address's row");
        assertEquals("exec-42", all.get(0).executionId());
        assertEquals("CONT-42", all.get(0).contentId());
    }

    @Test
    void newsAndEventsAreDistinctRecords() {
        seedAddress("ADDR-N3");
        seedAddress("ADDR-N4");
        store.saveAll("ADDR-N3", "news", List.of(new NewsEventItem(
                "ADDR-N3", "news", "Same title", null,
                "https://example.eu/n1", null)), null, null);
        store.saveAll("ADDR-N4", "event", List.of(new NewsEventItem(
                "ADDR-N4", "event", "Same title", null,
                "https://example.eu/n1", null)), null, null);

        assertEquals("news", store.byAddress("ADDR-N3").get(0).kind());
        assertEquals("event", store.byAddress("ADDR-N4").get(0).kind());
    }
}
