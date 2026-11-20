package es.jllopez.neis.application;

import es.jllopez.neis.domain.model.Address;
import es.jllopez.neis.domain.model.AddressType;
import es.jllopez.neis.testsupport.InMemoryContentStorePort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The remote side of the content export (US-18.8, issue #126): the
 * export runs into a bounded token store, the job result carries the
 * metadata and the download path (never the bytes), tokens are strict
 * and bounded-retention. */
class RemoteExportServiceTest {

    private static final Address ADDR = new Address("ADDR-030",
            "Remote export", null, "https://example.eu/remote-listing",
            AddressType.URL_NEWS);

    @TempDir
    Path tmp;

    private ContentStoreService storeWithContent() {
        ContentStoreService store = new ContentStoreService(
                new InMemoryContentStorePort(java.util.List.of()),
                tmp.resolve("content-root").toString());
        store.writeTaskOutput(ADDR, ADDR.url(), "WEBSCRAPE", "listing-html",
                "scrape.html", "<html>remote listing</html>", "EXEC-r1");
        return store;
    }

    @Test
    void exportProducesMetadataAndDownloadPathNeverBytes() throws Exception {
        ContentStoreService store = storeWithContent();
        RemoteExportService service = new RemoteExportService(
                new ContentExportService(store), store, "");
        String json = service.runExport("ADDR-030", null);
        assertTrue(json.contains("\"downloadUrl\":\"/api/exports/EXP-"),
                "the metadata carries the download path: " + json);
        assertTrue(json.contains("\"entries\":2"),
                "artifact + fetch record: " + json);
        assertTrue(json.contains("content/WEBSCRAPE/scrape.html"),
                "the file list names the artifacts: " + json);
        assertFalse(json.contains("<html>"),
                "the archive BYTES never ride in the result");
        // The archive exists under the retention store.
        String token = json.split("\"token\":\"")[1].split("\"")[0];
        assertTrue(service.archive(token).isPresent(),
                "the token resolves to the archive");
        assertTrue(Files.size(service.archive(token).orElseThrow()) > 0);
    }

    @Test
    void selectorRulesMatchTheRestContract() {
        ContentStoreService store = storeWithContent();
        RemoteExportService service = new RemoteExportService(
                new ContentExportService(store), store, "");
        assertThrows(IllegalArgumentException.class,
                () -> service.runExport(null, null),
                "no selector refused");
        assertThrows(IllegalArgumentException.class,
                () -> service.runExport("ADDR-030", "EXEC-r1"),
                "both selectors refused");
        IllegalArgumentException e = assertThrows(
                IllegalArgumentException.class,
                () -> service.runExport("ADDR-999", null));
        assertTrue(e.getMessage().contains("No stored content"),
                "unknown address refused loud: " + e.getMessage());
    }

    @Test
    void craftedTokensNeverResolveInsideTheRetentionStore() {
        ContentStoreService store = storeWithContent();
        RemoteExportService service = new RemoteExportService(
                new ContentExportService(store), store, "");
        assertTrue(service.archive("../content-root").isEmpty(),
                "traversal refused");
        assertTrue(service.archive("EXP-<script>").isEmpty(),
                "a non-token shape refused");
        assertTrue(service.archive("EXP-0123456789abcdef").isEmpty(),
                "a well-formed token with no archive is empty (expired "
                        + "or unknown)");
    }

    @Test
    void retentionBoundIsValidatedAndExpiredArchivesAreRemoved()
            throws Exception {
        // US-06.15 pattern: unknown values refuse the boot; zero/negative.
        assertThrows(IllegalArgumentException.class,
                () -> RemoteExportService.parseRetention("yesterday"));
        assertThrows(IllegalArgumentException.class,
                () -> RemoteExportService.parseRetention("PT0S"));
        assertEquals(Duration.ofHours(24),
                RemoteExportService.parseRetention(""),
                "blank resolves to the 24h default");

        // The expired-cleanup: a folder older than the bound is removed.
        ContentStoreService store = storeWithContent();
        RemoteExportService service = new RemoteExportService(
                new ContentExportService(store), store, "");
        String json = service.runExport("ADDR-030", null);
        String token = json.split("\"token\":\"")[1].split("\"")[0];
        Path folder = tmp.resolve("content-root").resolve("remote-exports")
                .resolve(token);
        // Backdate the folder past the (fresh) 24h default bound.
        Files.setLastModifiedTime(folder,
                java.nio.file.attribute.FileTime.from(
                        java.time.Instant.now().minus(Duration.ofHours(25))));
        service.runExport("ADDR-030", null); // a new submit cleans up
        assertFalse(Files.exists(folder),
                "the expired archive is removed (bounded store)");
    }
}
