package es.jllopez.neis.application;

import es.jllopez.neis.domain.model.Address;
import es.jllopez.neis.domain.model.AddressType;
import es.jllopez.neis.testsupport.InMemoryContentStorePort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The content tree (US-11, issue #11): per-address folders (US-11.1),
 * task outputs as content in per-task folders (US-11.2, US-11.3),
 * deterministic capture ids and database-backed folders (US-11.4).
 */
class ContentStoreServiceTest {

    @TempDir
    Path workDir;

    private final Address address = new Address("ADDR-001", "Source",
            "https://example.eu/news", AddressType.URL_NEWS);

    private ContentStoreService service() {
        return new ContentStoreService(new InMemoryContentStorePort(), workDir.toString());
    }

    @Test
    void captureIdsAreDeterministicFromTheUrlNotTheRun() {
        ContentStoreService store = service();
        String first = store.captureId("https://example.eu/news");
        String again = new ContentStoreService(new InMemoryContentStorePort(),
                workDir.toString()).captureId("https://example.eu/news");

        assertEquals(first, again, "the same webpage must always map to the same id");
        assertTrue(first.startsWith("cap-"));
        assertNotEquals(first, store.captureId("https://example.eu/news?page=2"),
                "a different webpage maps to a different capture");
    }

    @Test
    void taskOutputsLandInTheDeterministicPerTaskTree() throws Exception {
        ContentStoreService store = service();
        String captureId = store.captureId(address.url());

        Path scrape = store.writeTaskOutput(address, address.url(), "WEBSCRAPE",
                "listing-html", "scrape.html", "<html>listing</html>");
        Path simplified = store.writeTaskOutput(address, address.url(), "SIMPLIFY",
                "simplified-html", "simplified.html", "<html>simplified</html>");

        Path captureDir = workDir.resolve("ADDR-001").resolve(captureId);
        assertEquals(captureDir.resolve("content").resolve("WEBSCRAPE")
                .resolve("scrape.html"), scrape);
        assertEquals(captureDir.resolve("content").resolve("SIMPLIFY")
                .resolve("simplified.html"), simplified);
        assertTrue(Files.readString(scrape).contains("listing"));
        // The capture folder is backed by an entity and a fetch record (US-11.4).
        assertTrue(Files.exists(captureDir.resolve("metadata").resolve("capture.json")));
        assertTrue(Files.readString(captureDir.resolve("metadata").resolve("capture.json"))
                .contains(address.url()));
    }

    @Test
    void rerunningTheSameTaskUpdatesTheSameRecordInsteadOfDuplicating()
            throws Exception {
        ContentStoreService store = service();
        store.writeTaskOutput(address, address.url(), "WEBSCRAPE",
                "listing-html", "scrape.html", "<html>v1</html>");
        Path second = store.writeTaskOutput(address, address.url(), "WEBSCRAPE",
                "listing-html", "scrape.html", "<html>v2</html>");

        List<?> records = store.listByAddress(address.id());
        assertEquals(1, records.size(),
                "deterministic record ids: the re-run UPDATES the record");
        assertTrue(Files.readString(second).contains("v2"),
                "the file itself is overwritten in place (same folder)");
    }

    /**
     * US-11.6 (issue #139): the cache-source query — what a cached
     * fetch (US-06.14) would serve, with its identity and original
     * scrape timestamp. The overwrite-upsert semantics ARE the
     * retention guarantee today: the record never disappears (nothing
     * prunes content), the re-run updates it in place, and non-scrape
     * records are never served as the source.
     */
    @Test
    void theCacheSourceIsQueryableAndSurvivesReRuns() {
        ContentStoreService store = service();
        assertTrue(store.latestWebpageScrape(address).isEmpty(),
                "a never-scraped address has no cache source");

        store.writeTaskOutput(address, address.url(), "WEBSCRAPE",
                "listing-html", "scrape.html", "<html>v1</html>");
        var first = store.latestWebpageScrape(address);
        assertTrue(first.isPresent());
        assertEquals("WEBSCRAPE", first.get().taskCode());
        assertTrue(first.get().createdAt() != null,
                "the original scrape timestamp rides the source");

        // the re-run updates the SAME record — the source survives,
        // its file now carries the newer content
        store.writeTaskOutput(address, address.url(), "WEBSCRAPE",
                "listing-html", "scrape.html", "<html>v2</html>");
        var second = store.latestWebpageScrape(address);
        assertEquals(first.get().id(), second.get().id(),
                "the re-run updates the cache source in place — it never "
                        + "disappears (US-11.6 retention)");
        assertTrue(second.get().createdAt().isAfter(first.get().createdAt()),
                "the re-run REFRESHES the timestamp — the cache source is "
                        + "the LATEST scrape");
        assertTrue(store.listByAddress(address.id()).size() >= 1);

        // a SIMPLIFY record is never the cache source
        store.writeTaskOutput(address, address.url(), "SIMPLIFY",
                "simplified-html", "simplified.html", "<html>s</html>");
        assertEquals("WEBSCRAPE",
                store.latestWebpageScrape(address).get().taskCode(),
                "only the WEBSCRAPE listing is the cache source");
    }

    @Test
    void aDifferentWebpageOfTheSameAddressGetsItsOwnCaptureFolder() {
        ContentStoreService store = service();
        store.writeTaskOutput(address, address.url(), "WEBSCRAPE",
                "listing-html", "scrape.html", "<html>one</html>");
        store.writeTaskOutput(address, "https://example.eu/news?page=2", "WEBSCRAPE",
                "page-html", "scrape-page2.html", "<html>two</html>");

        assertEquals(2, store.listByAddress(address.id()).size());
        assertNotEquals(
                workDir.resolve("ADDR-001").resolve(store.captureId(address.url())),
                workDir.resolve("ADDR-001").resolve(store.captureId("https://example.eu/news?page=2")),
                "one folder per webpage capture (US-11.4)");
    }

    @Test
    void aHostilePathSegmentIsRefusedNotResolved() {
        ContentStoreService store = service();
        // The store is a public application-layer API: traversal and
        // separators must be REFUSED (review security finding), never
        // resolved outside the capture folder.
        assertThrows(IllegalArgumentException.class, () -> store.writeTaskOutput(
                address, address.url(), "WEBSCRAPE", "listing-html",
                "../../etc/passwd", "x"));
        assertThrows(IllegalArgumentException.class, () -> store.writeTaskOutput(
                address, address.url(), "../WEBSCRAPE", "listing-html",
                "scrape.html", "x"));
        assertThrows(IllegalArgumentException.class, () -> store.writeTaskOutput(
                address, address.url(), "WEBSCRAPE", "listing-html",
                "sub/dir/scrape.html", "x"));
        assertTrue(store.listByAddress(address.id()).isEmpty(),
                "refused writes must not record content");
    }

    @Test
    void chainStagesOrganizeContentPerTaskFolder() {
        // US-11.3: a chain's content lands in each constituent task's folder.
        ContentStoreService store = service();
        store.writeTaskOutput(address, address.url(), "WEBSCRAPE",
                "listing-html", "scrape.html", "<html>s</html>");
        store.writeTaskOutput(address, address.url(), "SIMPLIFY",
                "simplified-html", "simplified.html", "<html>si</html>");
        store.writeTaskOutput(address, address.url(), "FILTER",
                "filtered-html", "scrape-filtered.html", "<html>f</html>");

        Path content = workDir.resolve("ADDR-001")
                .resolve(store.captureId(address.url())).resolve("content");
        assertTrue(Files.exists(content.resolve("WEBSCRAPE").resolve("scrape.html")));
        assertTrue(Files.exists(content.resolve("SIMPLIFY").resolve("simplified.html")));
        assertTrue(Files.exists(content.resolve("FILTER").resolve("scrape-filtered.html")));
    }
}
