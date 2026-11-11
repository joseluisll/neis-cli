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
    void chainStagesOrganizeContentPerTaskFolder() {
        // US-11.3: a chain's content lands in each constituent task's folder.
        ContentStoreService store = service();
        store.writeTaskOutput(address, address.url(), "SCRAPE",
                "listing-html", "scrape.html", "<html>s</html>");
        store.writeTaskOutput(address, address.url(), "SIMPLIFY",
                "simplified-html", "simplified.html", "<html>si</html>");
        store.writeTaskOutput(address, address.url(), "FILTER",
                "filtered-html", "scrape-filtered.html", "<html>f</html>");

        Path content = workDir.resolve("ADDR-001")
                .resolve(store.captureId(address.url())).resolve("content");
        assertTrue(Files.exists(content.resolve("SCRAPE").resolve("scrape.html")));
        assertTrue(Files.exists(content.resolve("SIMPLIFY").resolve("simplified.html")));
        assertTrue(Files.exists(content.resolve("FILTER").resolve("scrape-filtered.html")));
    }
}
