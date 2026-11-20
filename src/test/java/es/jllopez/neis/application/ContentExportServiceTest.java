package es.jllopez.neis.application;

import es.jllopez.neis.domain.model.Address;
import es.jllopez.neis.domain.model.AddressType;
import es.jllopez.neis.testsupport.InMemoryContentStorePort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Content export (US-11.5, issue #125): a captured tree bundles as a
 * ZIP into a local folder — by execution or by address — with a
 * storage-tree-mirroring layout, a deterministic name and no silent
 * overwrite of a colliding archive. */
class ContentExportServiceTest {

    private static final Address ADDR = new Address("ADDR-020",
            "Export source", null, "https://example.eu/export-listing",
            AddressType.URL_NEWS);

    @TempDir
    Path tmp;

    private ContentExportService serviceWithContent() {
        ContentStoreService store = new ContentStoreService(
                new InMemoryContentStorePort(java.util.List.of()),
                tmp.resolve("content-root").toString());
        // One execution's artifacts across two per-task folders
        // (US-11.3 layout), recorded with the execution id.
        store.writeTaskOutput(ADDR, ADDR.url(), "WEBSCRAPE", "listing-html",
                "scrape.html", "<html>listing</html>", "EXEC-exp1");
        store.writeTaskOutput(ADDR, ADDR.url(), "SIMPLIFY", "simplify",
                "simplify.html", "<html>clean listing</html>", "EXEC-exp1");
        return new ContentExportService(store);
    }

    @Test
    void exportsAnExecutionAsTheStorageTreeMirror() throws Exception {
        ContentExportService service = serviceWithContent();
        Path out = tmp.resolve("out");
        Files.createDirectories(out);

        ContentExportService.ExportResult result =
                service.exportExecution("EXEC-exp1", out);

        assertEquals(out.resolve("neis-content-ADDR-020-EXEC-exp1.zip"),
                result.archive(), "the deterministic archive name");
        assertTrue(Files.isRegularFile(result.archive()));
        // The layout mirrors the storage tree: address / capture /
        // content / task / file, plus the fetch record.
        try (ZipFile zip = new ZipFile(result.archive().toFile())) {
            var names = zip.stream().map(ZipEntry::getName).toList();
            assertTrue(names.stream().anyMatch(n -> n.startsWith("ADDR-020/")
                            && n.contains("/content/WEBSCRAPE/scrape.html")),
                    "the WEBSCRAPE artifact rides under the tree mirror: "
                            + names);
            assertTrue(names.stream().anyMatch(n ->
                            n.contains("/content/SIMPLIFY/simplify.html")),
                    "the SIMPLIFY artifact rides too: " + names);
            assertTrue(names.stream().anyMatch(n ->
                            n.endsWith("/metadata/capture.json")),
                    "the fetch record rides along: " + names);
            assertEquals(3, result.entries(), "2 artifacts + 1 fetch record");
        }
        assertEquals("<html>listing</html>",
                Files.readString(unpack(result.archive(),
                        "ADDR-020/" + serviceCaptureId()
                                + "/content/WEBSCRAPE/scrape.html")),
                "the unpacked artifact is byte-identical");
    }

    @Test
    void exportsAnAddressWholeTreeByDefaultFolder() throws Exception {
        ContentExportService service = serviceWithContent();
        ContentExportService.ExportResult result =
                service.exportAddress("ADDR-020", null);
        assertEquals(Path.of(System.getProperty("java.io.tmpdir"))
                        .resolve("neis-content-ADDR-020.zip"),
                result.archive(),
                "the default folder is java.io.tmpdir and the name is "
                        + "deterministic");
        assertTrue(Files.size(result.archive()) > 0);
        // Cleanup: keep the shared tmp dir tidy for other tests.
        Files.deleteIfExists(result.archive());
    }

    @Test
    void aCollidingArchiveIsNeverSilentlyOverwritten() {
        ContentExportService service = serviceWithContent();
        Path out = tmp.resolve("out2");
        createDirectories(out);
        service.exportAddress("ADDR-020", out);
        IllegalStateException e = assertThrows(
                IllegalStateException.class,
                () -> service.exportAddress("ADDR-020", out),
                "the second export of the same tree collides");
        assertTrue(e.getMessage().contains("never silently overwritten"),
                "the refusal says so: " + e.getMessage());
    }

    @Test
    void unknownExecutionAndAddressAreRefusedLoud() {
        ContentExportService service = serviceWithContent();
        IllegalArgumentException e = assertThrows(
                IllegalArgumentException.class,
                () -> service.exportExecution("EXEC-none", tmp));
        assertTrue(e.getMessage().contains("No stored content"));
        assertThrows(IllegalArgumentException.class,
                () -> service.exportAddress("ADDR-999", tmp));
    }

    private static void createDirectories(Path dir) {
        try {
            Files.createDirectories(dir);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private String serviceCaptureId() {
        // The capture id is the service's deterministic formula; the
        // export test does not hardcode it — derive from the layout.
        ContentStoreService store = new ContentStoreService(
                new InMemoryContentStorePort(java.util.List.of()),
                tmp.resolve("content-root").toString());
        return store.captureId(ADDR.url());
    }

    private Path unpack(Path archive, String entryName) throws Exception {
        Path target = tmp.resolve("unpacked").resolve(entryName);
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            ZipEntry entry = zip.getEntry(entryName);
            if (entry == null) {
                throw new IllegalStateException("Entry missing in archive: "
                        + entryName);
            }
            Files.createDirectories(target.getParent());
            Files.copy(zip.getInputStream(entry), target,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        return target;
    }
}
