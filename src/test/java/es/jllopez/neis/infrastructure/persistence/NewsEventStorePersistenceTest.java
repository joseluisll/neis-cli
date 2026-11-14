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

        assertEquals(1, store.saveAll("ADDR-N1", "news", List.of(item)));
        // Re-extraction of the same item: upsert, not a duplicate.
        assertEquals(1, store.saveAll("ADDR-N1", "news", List.of(item)));
        assertEquals(1, store.byAddress("ADDR-N1").size());

        // A changed title for the same URL updates the same row.
        var renamed = new NewsEventItem("ADDR-N1", "news", "Title (updated)",
                "Summary", "https://example.eu/news/1", "2026-11-14");
        store.saveAll("ADDR-N1", "news", List.of(renamed));
        assertEquals(1, store.byAddress("ADDR-N1").size());
        assertEquals("Title (updated)", store.byAddress("ADDR-N1").get(0).title());
    }

    @Test
    void linkLessItemsAreKeyedOnTitle() {
        seedAddress("ADDR-N2");
        var item = new NewsEventItem("ADDR-N2", "event", "Event without a link",
                null, null, "2026-11-14");

        store.saveAll("ADDR-N2", "event", List.of(item));
        store.saveAll("ADDR-N2", "event", List.of(item));

        assertEquals(1, store.byAddress("ADDR-N2").size(),
                "no duplicate rows for link-less items");
        assertEquals("event", store.byAddress("ADDR-N2").get(0).kind());
    }

    @Test
    void newsAndEventsAreDistinctRecords() {
        seedAddress("ADDR-N3");
        seedAddress("ADDR-N4");
        store.saveAll("ADDR-N3", "news", List.of(new NewsEventItem(
                "ADDR-N3", "news", "Same title", null,
                "https://example.eu/n1", null)));
        store.saveAll("ADDR-N4", "event", List.of(new NewsEventItem(
                "ADDR-N4", "event", "Same title", null,
                "https://example.eu/n1", null)));

        assertEquals("news", store.byAddress("ADDR-N3").get(0).kind());
        assertEquals("event", store.byAddress("ADDR-N4").get(0).kind());
    }
}
