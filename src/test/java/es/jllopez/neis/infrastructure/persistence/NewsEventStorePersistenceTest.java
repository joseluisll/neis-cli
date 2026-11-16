package es.jllopez.neis.infrastructure.persistence;

import com.zaxxer.hikari.HikariDataSource;
import es.jllopez.neis.domain.model.NewsEventItem;
import es.jllopez.neis.domain.model.NewsEventQuery;
import es.jllopez.neis.domain.model.NewsEventSighting;
import es.jllopez.neis.domain.port.NewsEventStorePort;
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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void searchFiltersByKindAddressAndDateRange() {
        // US-14.3: the repository is queryable — every criterion
        // optional, date range lexical on the ISO TEXT item date, rows
        // without a date excluded while a range is active.
        seedAddress("ADDR-N9");
        seedAddress("ADDR-NA");
        store.saveAll("ADDR-N9", "news", List.of(new NewsEventItem(
                "ADDR-N9", "news", "Nov 1 news", null,
                "https://example.eu/s1", "2026-11-01")), null, null);
        store.saveAll("ADDR-N9", "news", List.of(new NewsEventItem(
                "ADDR-N9", "news", "Nov 20 news", null,
                "https://example.eu/s2", "2026-11-20")), null, null);
        store.saveAll("ADDR-NA", "event", List.of(new NewsEventItem(
                "ADDR-NA", "event", "Nov 10 event", null,
                "https://example.eu/s3", "2026-11-10")), null, null);
        // The real extracted dates are human-formatted (verified live
        // during US-14.3) — the range must understand them too.
        store.saveAll("ADDR-NA", "event", List.of(new NewsEventItem(
                "ADDR-NA", "event", "Human-dated event", null,
                "https://example.eu/s5", "Nov 12, 2026")), null, null);
        store.saveAll("ADDR-NA", "event", List.of(new NewsEventItem(
                "ADDR-NA", "event", "Undated event", null,
                "https://example.eu/s4", null)), null, null);

        // By kind, scoped to this test's addresses (the suite shares
        // one SQLite file — sibling tests seed other rows).
        assertEquals(2, store.search(new NewsEventQuery(
                "ADDR-N9", "news", null, null, 20)).size());
        assertEquals(3, store.search(new NewsEventQuery(
                "ADDR-NA", "event", null, null, 20)).size());
        assertEquals(0, store.search(new NewsEventQuery(
                "ADDR-N9", "event", null, null, 20)).size(),
                "kind filters within the address");

        // By date range: the undated row is excluded; the ISO and the
        // human-formatted rows both parse.
        var window = store.search(new NewsEventQuery(
                "ADDR-NA", null, "2026-11-05", "2026-11-15", 20));
        assertEquals(2, window.size());
        assertTrue(window.stream().anyMatch(i -> "Nov 10 event".equals(i.title())));
        assertTrue(window.stream().anyMatch(i -> "Human-dated event".equals(i.title())));

        // Combined: address + kind + range.
        var combined = store.search(new NewsEventQuery(
                "ADDR-N9", "news", "2026-11-15", null, 20));
        assertEquals(1, combined.size());
        assertEquals("Nov 20 news", combined.get(0).title());

        // Limit caps the result (newest first is the ordering contract).
        var limited = store.search(new NewsEventQuery(null, null, null, null, 2));
        assertEquals(2, limited.size());
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

    @Test
    void optionalMetadataRoundTrips() {
        // US-14.4: picture and location are captured when present and
        // survive the read path.
        seedAddress("ADDR-NB");
        store.saveAll("ADDR-NB", "event", List.of(new NewsEventItem(
                "ADDR-NB", "event", "Rich event", "Description",
                "https://example.eu/rich", "2026-12-01")), null, null);
        var stored = NewsEventItem.extracted("ADDR-NB", "event",
                "Rich event", "Description", "https://example.eu/rich",
                "2026-12-01", "https://example.eu/img.jpg", "Brussels");
        store.saveAll("ADDR-NB", "event", List.of(stored), null, null);

        var read = store.byAddress("ADDR-NB");
        assertEquals(1, read.size(), "the optional fields are not part of the key");
        assertEquals("https://example.eu/img.jpg", read.get(0).picture());
        assertEquals("Brussels", read.get(0).location());
    }

    @Test
    void missingMandatoryDataIsFlaggedIncompleteWithTheMissingFields() {
        // US-14.4: a record missing mandatory data is not silently
        // stored as complete — flagged incomplete, missing fields listed.
        seedAddress("ADDR-NC");
        store.saveAll("ADDR-NC", "event", List.of(new NewsEventItem(
                "ADDR-NC", "event", "Sparse event", null,
                "https://example.eu/sparse", null)), null, null);

        var read = store.byAddress("ADDR-NC");
        assertEquals(1, read.size());
        assertTrue(read.get(0).incomplete());
        assertEquals("description, date", read.get(0).missingFields());
    }

    @Test
    void upsertThatFixesTheDataClearsTheIncompleteFlag() {
        // US-14.4: completeness is re-evaluated on every save — a later
        // extraction that finds the missing data completes the record.
        seedAddress("ADDR-ND");
        var sparse = new NewsEventItem("ADDR-ND", "news", "Item",
                null, "https://example.eu/fix", null);
        store.saveAll("ADDR-ND", "news", List.of(sparse), null, null);
        assertTrue(store.byAddress("ADDR-ND").get(0).incomplete());

        var complete = new NewsEventItem("ADDR-ND", "news", "Item",
                "Now described", "https://example.eu/fix", "2026-11-14");
        store.saveAll("ADDR-ND", "news", List.of(complete), null, null);

        var read = store.byAddress("ADDR-ND");
        assertEquals(1, read.size());
        assertTrue(!read.get(0).incomplete(), "flag cleared by the fixing upsert");
        assertEquals(null, read.get(0).missingFields());
    }

    @Test
    void nullTitleIsStoredFlaggedNotAbortingTheBatch() {
        // US-14.4 review: the schema's title NOT NULL must not abort
        // saveAll for a title-less item — the row is stored (empty
        // title) flagged incomplete with "title" among the missing
        // fields, and the REST of the batch still lands.
        seedAddress("ADDR-NE");
        var titleless = new NewsEventItem("ADDR-NE", "event", null,
                "Description", "https://example.eu/notitle", "2026-12-01");
        var healthy = new NewsEventItem("ADDR-NE", "event", "Healthy item",
                "Description", "https://example.eu/healthy", "2026-12-01");

        assertEquals(2, store.saveAll("ADDR-NE", "event",
                List.of(titleless, healthy), null, null),
                "the null-title row does not abort the batch");

        var byTitle = store.byAddress("ADDR-NE").stream()
                .filter(item -> item.url() != null
                        && item.url().endsWith("notitle"))
                .findFirst().orElseThrow();
        assertTrue(byTitle.incomplete());
        assertTrue(byTitle.missingFields().contains("title"));
        assertEquals("", byTitle.title());

        var healthyRow = store.byAddress("ADDR-NE").stream()
                .filter(item -> "Healthy item".equals(item.title()))
                .findFirst().orElseThrow();
        assertTrue(!healthyRow.incomplete());
    }

    @Test
    void reEncounterAddsSightingNotRecord() {
        // US-14.5: re-running the extraction over the same address
        // updates the existing row and records a sighting per capture —
        // no duplicate records.
        seedAddress("ADDR-NF");
        var item = new NewsEventItem("ADDR-NF", "news", "Seen again",
                "Description", "https://example.eu/again", "2026-11-14");

        assertEquals(1, store.saveAll("ADDR-NF", "news",
                List.of(item), "exec-1", "CONT-1"));
        assertEquals(1, store.saveAll("ADDR-NF", "news",
                List.of(item), "exec-2", "CONT-2"));

        assertEquals(1, store.byAddress("ADDR-NF").size(),
                "still one record");
        var canonicalId = store.byAddress("ADDR-NF").get(0).canonicalId();
        var sightings = store.sightings(canonicalId);
        assertEquals(2, sightings.size(), "two captures, two sightings");
        assertTrue(sightings.stream().anyMatch(s ->
                "exec-1".equals(s.executionId()) && "CONT-1".equals(s.contentId())));
        assertTrue(sightings.stream().anyMatch(s ->
                "exec-2".equals(s.executionId()) && "CONT-2".equals(s.contentId())));
    }

    @Test
    void sameStoryOnAnotherAddressJoinsTheCanonicalRecord() {
        // US-14.5 cross-address dedup: the same story (same URL, or
        // same title+date when there is no link) seen from another
        // address shares the canonical id — one logical record — while
        // each address keeps its own row.
        seedAddress("ADDR-NG");
        seedAddress("ADDR-NH");
        store.saveAll("ADDR-NG", "news", List.of(new NewsEventItem(
                "ADDR-NG", "news", "Shared story", "d",
                "https://example.eu/shared", "2026-11-14")), null, null);
        store.saveAll("ADDR-NH", "news", List.of(new NewsEventItem(
                "ADDR-NH", "news", "SHARED   story", null,
                "https://example.eu/shared", "2026-11-14")), null, null);

        assertEquals(1, store.byAddress("ADDR-NG").size());
        assertEquals(1, store.byAddress("ADDR-NH").size());
        assertEquals(store.byAddress("ADDR-NG").get(0).canonicalId(),
                store.byAddress("ADDR-NH").get(0).canonicalId(),
                "the two rows are one logical record");

        // The sightings of the logical record span both addresses.
        var canonicalId = store.byAddress("ADDR-NG").get(0).canonicalId();
        assertEquals(2, store.sightings(canonicalId).size());
        assertEquals(2, store.sightings(canonicalId).stream()
                .map(NewsEventSighting::addressId).distinct().count());
    }

    @Test
    void fullTextSearchMatchesTitleDescriptionAndLocation() {
        // US-14.7: terms match title, description and location; results
        // combined with the kind/address filters.
        seedAddress("ADDR-NJ");
        store.saveAll("ADDR-NJ", "news", List.of(new NewsEventItem(
                "ADDR-NJ", "news", "Cybersecurity workshop announced",
                "Hands-on sessions on threat modelling",
                "https://example.eu/fts1", "2026-11-14")), null, null);
        store.saveAll("ADDR-NJ", "event", List.of(NewsEventItem.extracted(
                "ADDR-NJ", "event", "Annual meet-up", "Networking evening",
                "https://example.eu/fts2", "2026-12-01",
                null, "Brussels")), null, null);
        store.saveAll("ADDR-NJ", "news", List.of(new NewsEventItem(
                "ADDR-NJ", "news", "Unrelated item", "Nothing to see here",
                "https://example.eu/fts3", "2026-11-15")), null, null);

        // Title match.
        assertEquals(1, store.search("cybersecurity", new NewsEventQuery(
                "ADDR-NJ", null, null, null, 20)).size());
        // Description match.
        assertEquals(1, store.search("threat", new NewsEventQuery(
                "ADDR-NJ", null, null, null, 20)).size());
        // Location (optional metadata) match.
        assertEquals(1, store.search("brussels", new NewsEventQuery(
                "ADDR-NJ", null, null, null, 20)).size());
        // Combined with kind and address filters.
        assertEquals(0, store.search("brussels", new NewsEventQuery(
                "ADDR-NJ", "news", null, null, 20)).size(),
                "the location hit is an event — kind filter excludes it");
        // All terms must match (AND).
        assertEquals(1, store.search("cybersecurity workshop", new NewsEventQuery(
                "ADDR-NJ", null, null, null, 20)).size());
        assertEquals(0, store.search("cybersecurity brussels", new NewsEventQuery(
                "ADDR-NJ", null, null, null, 20)).size());
        // Date range combines on top (lenient).
        assertEquals(1, store.search("annual", new NewsEventQuery(
                "ADDR-NJ", null, "2026-11-30", null, 20)).size());
    }

    @Test
    void ftsIndexStaysConsistentWithTheRepository() {
        // US-14.7: new, updated and removed records are reflected by
        // the index (V7 triggers) — this test runs on the real SQLite
        // connector, so the triggers are exercised.
        seedAddress("ADDR-NK");
        store.saveAll("ADDR-NK", "news", List.of(new NewsEventItem(
                "ADDR-NK", "news", "Quantum computing briefing",
                "Description", "https://example.eu/q1", "2026-11-14")),
                null, null);
        assertEquals(1, store.search("quantum", new NewsEventQuery(
                "ADDR-NK", null, null, null, 20)).size(),
                "a new record is searchable");

        // Updated record: the replaced token no longer matches, the new
        // one does (note: 'Post-quantum' still contains the token
        // 'quantum' — the hyphen splits tokens — so the replaced token
        // probed here is 'computing').
        store.saveAll("ADDR-NK", "news", List.of(new NewsEventItem(
                "ADDR-NK", "news", "Post-quantum cryptography briefing",
                "Description", "https://example.eu/q1", "2026-11-14")),
                null, null);
        assertEquals(0, store.search("computing", new NewsEventQuery(
                "ADDR-NK", null, null, null, 20)).size());
        assertEquals(1, store.search("cryptography", new NewsEventQuery(
                "ADDR-NK", null, null, null, 20)).size());

        // Removed record: direct SQL deletes exercise the delete
        // trigger (the sightings rows go first — the sightings FK
        // references the item).
        jdbcTemplate.update(
                "DELETE FROM news_event_sightings WHERE address_id = 'ADDR-NK'");
        jdbcTemplate.update("DELETE FROM news_events WHERE address_id = 'ADDR-NK'");
        assertEquals(0, store.search("post-quantum", new NewsEventQuery(
                "ADDR-NK", null, null, null, 20)).size(),
                "a removed record leaves the index");
    }

    @Test
    void ftsSyntaxInUserTermsCannotInjectOrCrashTheMatch() {
        seedAddress("ADDR-NL");
        store.saveAll("ADDR-NL", "news", List.of(new NewsEventItem(
                "ADDR-NL", "news", "Wallet roadmap discussion",
                "Description", "https://example.eu/w1", "2026-11-14")),
                null, null);
        // FTS5 operators and column filters in user terms are quoted
        // away: a punctuation-laden term becomes one quoted PHRASE, so
        // it can neither inject syntax nor crash the match — it simply
        // matches nothing (the plain term 'wallet' still finds the row).
        assertEquals(1, store.search("wallet", new NewsEventQuery(
                "ADDR-NL", null, null, null, 20)).size());
        assertEquals(0, store.search("(wallet) OR title:wallet",
                new NewsEventQuery("ADDR-NL", null, null, null, 20)).size());
        assertEquals(0, store.search("NEAR(a b, 3)", new NewsEventQuery(
                "ADDR-NL", null, null, null, 20)).size());
    }

    @Test
    void embeddedQuotesInUserTermsStayTokenSeparators() {
        // US-14.7 review nit: embedded double quotes are ESCAPED by
        // doubling (FTS5 string syntax), never stripped. A quote is a
        // token separator, so stripping would merge the tokens around
        // it: 'foo"bar' must search the phrase foo+bar (matching
        // 'foo bar') — not one merged token 'foobar'.
        seedAddress("ADDR-IT");
        store.saveAll("ADDR-IT", "news", List.of(new NewsEventItem(
                        "ADDR-IT", "news", "The foo bar baz story",
                        "Description", "https://example.eu/q1", "2026-11-14")),
                null, null);
        store.saveAll("ADDR-IT", "news", List.of(new NewsEventItem(
                        "ADDR-IT", "news", "A foobar word stands alone",
                        "Description", "https://example.eu/q2", "2026-11-14")),
                null, null);
        // The escape semantics are pinned by IDENTITY, not count: with
        // the old strip code 'foo"bar' also returned exactly one row —
        // the merged-token item. The phrase foo+bar must match the
        // foo-bar item, never the foobar one.
        List<NewsEventItem> phraseHits = store.search("foo\"bar",
                new NewsEventQuery("ADDR-IT", null, null, null, 20));
        assertEquals(1, phraseHits.size());
        assertEquals("The foo bar baz story", phraseHits.get(0).title(),
                "'foo\"bar' must match the foo bar item, not the merged foobar token");
        // A user term carrying its own quotes still matches: the
        // literal quotes are separators on both sides of the phrase.
        List<NewsEventItem> quotedHits = store.search("story \"foo\" bar",
                new NewsEventQuery("ADDR-IT", null, null, null, 20));
        assertEquals(1, quotedHits.size());
        assertEquals("The foo bar baz story", quotedHits.get(0).title());
    }
}
