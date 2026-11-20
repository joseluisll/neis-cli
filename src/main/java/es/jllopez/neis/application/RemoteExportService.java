package es.jllopez.neis.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * The remote side of the content export (US-18.8, issue #126): runs
 * the US-11.5 ZIP export into a BOUNDED retention store and hands out
 * a token-based download path. The archive never rides in the
 * response — the job's result carries the metadata (size, entry
 * count, file list) and the download URL; the ZIP streams on demand
 * through {@code GET /api/exports/{token}}.
 *
 * <p>Retention: every export lands in its own token folder under the
 * content root's {@code remote-exports/} directory; each submit
 * best-effort removes folders older than the configured bound
 * ({@code neis.server.export-retention}, default 24h) — a bounded
 * result store, never unbounded. A token is the folder name and must
 * match the strict token shape (a crafted token can never traverse).</p>
 */
@Service
public class RemoteExportService {

    private static final Logger log =
            LoggerFactory.getLogger(RemoteExportService.class);

    private final ContentExportService exports;
    private final String contentRoot;
    private final Duration retention;

    public RemoteExportService(ContentExportService exports,
                               ContentStoreService contentStore,
                               @Value("${neis.server.export-retention:PT24H}")
                               String retention) {
        this.exports = exports;
        this.contentRoot = contentStore.root();
        this.retention = parseRetention(retention);
    }

    /**
     * Parses the retention bound (US-06.15 pattern): blank resolves to
     * the 24h default; a value that does not parse fails the boot;
     * zero/negative is refused — a non-positive bound would delete
     * every archive immediately.
     */
    static Duration parseRetention(String configured) {
        String normalized = configured == null ? "" : configured.trim();
        if (normalized.isEmpty()) {
            return Duration.ofHours(24);
        }
        Duration parsed;
        try {
            parsed = Duration.parse(normalized);
        } catch (java.time.format.DateTimeParseException e) {
            throw new IllegalArgumentException(
                    "neis.server.export-retention '" + configured
                            + "' is not a valid ISO-8601 duration "
                            + "(e.g. PT24H, P7D) (US-18.8)", e);
        }
        if (parsed.isZero() || parsed.isNegative()) {
            throw new IllegalArgumentException(
                    "neis.server.export-retention must be positive, got "
                            + configured + " (US-18.8)");
        }
        return parsed;
    }

    /**
     * Runs the export synchronously (the remote job's runner calls
     * this) and returns the job-result JSON: the archive metadata
     * (entry count, content bytes, archive bytes, the file list) plus
     * the download path {@code /api/exports/<token>}. Exactly one
     * selector.
     */
    public String runExport(String addressId, String executionId) {
        boolean hasAddress = addressId != null && !addressId.isBlank();
        boolean hasExecution = executionId != null && !executionId.isBlank();
        if (hasAddress == hasExecution) {
            throw new IllegalArgumentException(
                    "Choose exactly one selector: address_id or "
                            + "execution_id (US-18.8)");
        }
        cleanupExpired();
        String token = "EXP-" + newToken();
        Path folder = retentionDir().resolve(token);
        try {
            Files.createDirectories(folder);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot create the export "
                    + "folder " + folder + " (US-18.8)", e);
        }
        try {
            ContentExportService.ExportResult result = hasAddress
                    ? exports.exportAddress(addressId.trim(), folder)
                    : exports.exportExecution(executionId.trim(), folder);
            String json = metadataJson(token, result);
            log.info("[Export] {} ready: {} ({} entries, download "
                            + "/api/exports/{}, retention {}; US-18.8)", token,
                    result.archive(), result.entries(), token, retention);
            return json;
        } catch (RuntimeException failed) {
            // A failed export must not leave the empty token folder
            // behind until retention cleanup (review note).
            try {
                Files.deleteIfExists(folder);
            } catch (IOException cleanup) {
                log.warn("[Export] could not remove the failed export "
                        + "folder {} (US-18.8)", folder, cleanup);
            }
            throw failed;
        }
    }

    /**
     * The archive file of a download token, when it still exists
     * (bounded retention — an expired token is gone, never an error
     * store). The token must match the strict shape: anything else is
     * a traversal attempt, refused.
     */
    public java.util.Optional<Path> archive(String token) {
        if (token == null || !token.matches("EXP-[0-9a-f]{16}")) {
            return java.util.Optional.empty();
        }
        Path archive = retentionDir().resolve(token);
        try (Stream<Path> files = Files.list(archive)) {
            return files.filter(f -> f.toString().endsWith(".zip"))
                    .findFirst();
        } catch (IOException e) {
            return java.util.Optional.empty();
        }
    }

    /**
     * Bounded retention (US-18.8): each submit best-effort removes
     * token folders older than the bound. Best-effort means a failed
     * cleanup logs and moves on — the export must not fail because a
     * previous archive could not be removed.
     */
    private void cleanupExpired() {
        Path dir = retentionDir();
        if (!Files.isDirectory(dir)) {
            return;
        }
        Instant cutoff = Instant.now().minus(retention);
        try (Stream<Path> folders = Files.list(dir)) {
            folders.filter(Files::isDirectory).forEach(folder -> {
                try {
                    Instant modified = Files.getLastModifiedTime(folder)
                            .toInstant();
                    if (modified.isBefore(cutoff)) {
                        deleteTree(folder);
                        log.info("[Export] retention: removed expired "
                                + "{} (bound {}; US-18.8)", folder.getFileName(),
                                retention);
                    }
                } catch (IOException e) {
                    log.warn("[Export] retention cleanup skipped {}: {}",
                            folder, e.toString());
                }
            });
        } catch (IOException e) {
            log.warn("[Export] retention cleanup skipped: {}", e.toString());
        }
    }

    private Path retentionDir() {
        return Path.of(contentRoot).resolve("remote-exports");
    }

    private static void deleteTree(Path folder) throws IOException {
        try (Stream<Path> walk = Files.walk(folder)) {
            walk.sorted(java.util.Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.delete(path);
                        } catch (IOException e) {
                            throw new IllegalStateException(e);
                        }
                    });
        }
    }

    /** 16 hex characters of a fresh random token. */
    private static String newToken() {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(
                    (Instant.now() + "|" + java.util.UUID.randomUUID())
                            .getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                sb.append(String.format("%02x", hash[i]));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /**
     * The job-result JSON: metadata + the documented download path
     * (relative to the server base — the same shape every caller
     * already knows from the REST surface).
     *
     * <p>Bounded by design (review blocking finding, issue #126): the
     * remote-jobs result column is 4096 chars and the job model
     * truncates beyond that — a blind cut would leave INVALID JSON.
     * So the builder enforces its own budget: the file list is
     * appended only while the projected document stays under it, and
     * a {@code filesTruncated} marker (with the omitted count) makes
     * any shortfall explicit. The critical fields (token,
     * downloadUrl, counts) come FIRST and are always complete, and
     * the server-local path is never exposed (file name only).</p>
     */
    private static String metadataJson(String token,
                                       ContentExportService.ExportResult result) {
        List<String> files = new ArrayList<>();
        try (ZipFile zip = new ZipFile(result.archive().toFile())) {
            zip.stream().map(ZipEntry::getName).forEach(files::add);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read the export "
                    + "archive " + result.archive() + " (US-18.8)", e);
        }
        StringBuilder json = new StringBuilder("{\"token\":\"").append(token)
                .append("\",\"downloadUrl\":\"/api/exports/").append(token)
                .append("\",\"archiveFile\":\"")
                .append(escape(result.archive().getFileName().toString()))
                .append("\",\"entries\":").append(result.entries())
                .append(",\"contentBytes\":").append(result.contentBytes())
                .append(",\"archiveBytes\":").append(result.archiveBytes())
                .append(",\"files\":[");
        // The budget leaves ample room under the job store's 4096-char
        // result column: fields before 'files' are ~200 chars, each
        // appended entry reserves its own length plus the closing
        // marker's length.
        int budget = 3200;
        int omitted = 0;
        boolean first = true;
        for (int i = 0; i < files.size(); i++) {
            String escaped = escape(files.get(i));
            if (json.length() + escaped.length() + 60 > budget) {
                omitted = files.size() - i;
                break;
            }
            if (!first) {
                json.append(',');
            }
            first = false;
            json.append('"').append(escaped).append('"');
        }
        json.append(']');
        if (omitted > 0) {
            json.append(",\"filesTruncated\":true,\"filesOmitted\":")
                    .append(omitted);
        }
        return json.append("}").toString();
    }

    /** Minimal JSON string escaping (the store emitter rule). */
    private static String escape(String value) {
        StringBuilder out = new StringBuilder(value.length() + 8);
        for (char c : value.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }
}
