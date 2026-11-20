package es.jllopez.neis.application;

import es.jllopez.neis.domain.model.ContentRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Use case: content export (US-11.5, issue #125) — bundles previously
 * captured content (scraped HTML5, SIMPLIFY output, PRINT reports,
 * ...) as a ZIP archive into a local folder, so the storage tree can
 * be taken away, not only inspected.
 *
 * <p>Selection: by EXECUTION (one capture's artifacts across its
 * per-task folders, US-11.3 layout) or by ADDRESS (the address's
 * whole content tree). The archive name is deterministic —
 * {@code neis-content-<address>[-<execution>].zip} — and a colliding
 * file is NEVER silently overwritten. The ZIP layout mirrors the
 * storage tree (address / capture / task), so an unpacked archive is
 * navigable with the same conventions as {@code content tree}; each
 * involved capture's fetch record ({@code metadata/capture.json})
 * rides along.</p>
 */
@Service
public class ContentExportService {

    private static final Logger log =
            LoggerFactory.getLogger(ContentExportService.class);

    private final ContentStoreService contentStore;

    public ContentExportService(ContentStoreService contentStore) {
        this.contentStore = contentStore;
    }

    /** The export outcome the command summarizes. */
    public record ExportResult(Path archive, int entries, long contentBytes,
                               long archiveBytes) {
    }

    /** Exports one execution's content (US-11.3 per-task folders). */
    public ExportResult exportExecution(String executionId, Path targetDir) {
        if (executionId == null || executionId.isBlank()) {
            throw new IllegalArgumentException(
                    "The execution id is required (US-11.5)");
        }
        List<ContentRecord> records =
                contentStore.listByExecution(executionId.trim());
        if (records.isEmpty()) {
            throw new IllegalArgumentException("No stored content for "
                    + "execution " + executionId.trim() + " — run a task "
                    + "or chain first, or check 'content list' (US-11.5)");
        }
        String addressId = records.get(0).addressId();
        return export(addressId, records, targetDir,
                "neis-content-" + addressId + "-" + executionId.trim()
                        + ".zip");
    }

    /** Exports an address's whole content tree. */
    public ExportResult exportAddress(String addressId, Path targetDir) {
        if (addressId == null || addressId.isBlank()) {
            throw new IllegalArgumentException(
                    "The address id is required (US-11.5)");
        }
        List<ContentRecord> records =
                contentStore.listByAddress(addressId.trim());
        if (records.isEmpty()) {
            throw new IllegalArgumentException("No stored content for "
                    + addressId.trim() + " — run a task or chain first, "
                    + "or check 'content list' (US-11.5)");
        }
        return export(addressId.trim(), records, targetDir,
                "neis-content-" + addressId.trim() + ".zip");
    }

    /**
     * The shared export path: every record's file becomes one ZIP
     * entry named relative to the CONTENT ROOT (the storage-tree
     * mirror), plus each involved capture's fetch record. The target
     * file must not exist — a colliding archive is refused, never
     * silently overwritten (the operator decides: move it or pick
     * another folder).
     */
    private ExportResult export(String addressId, List<ContentRecord> records,
                                Path targetDir, String archiveName) {
        Path root = Path.of(contentStore.root()).toAbsolutePath()
                .normalize();
        Path folder = (targetDir == null || targetDir.toString().isBlank())
                ? Path.of(System.getProperty("java.io.tmpdir"))
                : targetDir;
        if (!Files.isDirectory(folder)) {
            throw new IllegalArgumentException("The export folder "
                    + folder + " does not exist — create it first (US-11.5)");
        }
        Path archive = folder.resolve(archiveName).toAbsolutePath()
                .normalize();
        if (Files.exists(archive)) {
            throw new IllegalStateException("The archive " + archive
                    + " already exists — move it away or choose another "
                    + "folder; an existing archive is never silently "
                    + "overwritten (US-11.5)");
        }
        // Review hardening: the selectors are interpolated into entry
        // and archive names — the same segment rules the store applies
        // to task codes and file names apply here (no separators, no
        // traversal). Reachable only with a tampered store, but a
        // hostile id must never escape the export folder.
        validateSegment(addressId, "address id");
        List<String> captures = records.stream()
                .map(ContentRecord::captureId).distinct().toList();
        captures.forEach(id -> validateSegment(id, "capture id"));
        if (archiveName.contains("-EXEC-")) {
            validateSegment(executionSegment(archiveName), "execution id");
        }
        if (records.stream().map(ContentRecord::addressId).distinct()
                .count() > 1) {
            throw new IllegalArgumentException(
                    "The selection spans several addresses ("
                            + records.stream().map(ContentRecord::addressId)
                            .distinct().toList() + ") — export per address "
                            + "(US-11.5)");
        }

        long contentBytes = 0;
        int entries = 0;
        try (ZipOutputStream zip = new ZipOutputStream(
                Files.newOutputStream(archive))) {
            for (ContentRecord record : records) {
                Path file = Path.of(record.path());
                if (!Files.isRegularFile(file)) {
                    log.warn("Content export: stored file missing, "
                            + "skipped: {} (US-11.5)", file);
                    continue;
                }
                String entryName = entryName(root, file, addressId);
                long bytes = Files.size(file);
                zip.putNextEntry(new ZipEntry(entryName));
                Files.copy(file, zip);
                zip.closeEntry();
                entries++;
                contentBytes += bytes;
            }
            for (String captureId : captures) {
                Path fetchRecord = root.resolve(addressId)
                        .resolve(captureId).resolve("metadata")
                        .resolve("capture.json");
                if (!Files.isRegularFile(fetchRecord)) {
                    continue; // legacy capture without a fetch record
                }
                zip.putNextEntry(new ZipEntry(root.relativize(fetchRecord)
                        .toString().replace('\\', '/')));
                Files.copy(fetchRecord, zip);
                zip.closeEntry();
                entries++;
            }
        } catch (IOException e) {
            // Best-effort cleanup (review note): a failed export never
            // leaves a deceptive half-written archive at the
            // deterministic name — the operator must not mistake it
            // for a good one.
            try {
                Files.deleteIfExists(archive);
            } catch (IOException cleanup) {
                log.warn("Content export: could not remove the partial "
                        + "archive {} — remove it by hand before "
                        + "retrying (US-11.5)", archive, cleanup);
            }
            throw new IllegalStateException("Cannot write the export "
                    + "archive " + archive + " (US-11.5)", e);
        }
        long archiveBytes;
        try {
            archiveBytes = Files.size(archive);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot size the archive "
                    + archive, e);
        }
        log.info("Content export: {} ({} entries, {} bytes content, "
                + "{} bytes archive; US-11.5)", archive, entries,
                contentBytes, archiveBytes);
        return new ExportResult(archive, entries, contentBytes, archiveBytes);
    }

    /**
     * The ZIP entry name of a stored file: its path relative to the
     * content root (the storage-tree mirror). A file the store
     * recorded OUTSIDE the content root (a moved root, a legacy
     * record) falls back to address/capture/filename — still
     * navigable, never an absolute entry name.
     */
    private static String entryName(Path root, Path file,
                                    String addressId) {
        Path normalized = file.toAbsolutePath().normalize();
        if (normalized.startsWith(root)) {
            return root.relativize(normalized).toString()
                    .replace('\\', '/');
        }
        // Legacy fallback: keep the tree shape from the tail.
        Path capture = normalized.getParent();
        String captureName = capture == null ? "unknown-capture"
                : capture.getFileName().toString();
        return addressId + "/" + captureName + "/"
                + normalized.getFileName();
    }

    /** The store's own segment rules (no separators, no traversal). */
    private static void validateSegment(String segment, String what) {
        if (segment == null || segment.isBlank()
                || segment.contains("/") || segment.contains("\\")
                || segment.contains("..") || segment.startsWith(".")) {
            throw new IllegalArgumentException("Unsafe " + what
                    + ": '" + segment + "' (US-11.5)");
        }
    }

    /** The execution id embedded in the deterministic archive name. */
    private static String executionSegment(String archiveName) {
        int marker = archiveName.indexOf("-EXEC-");
        return marker < 0 ? archiveName
                : archiveName.substring(marker + 1, archiveName.length()
                        - ".zip".length());
    }
}
