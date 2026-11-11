package es.jllopez.neis.application;

import es.jllopez.neis.domain.model.Address;
import es.jllopez.neis.domain.model.Capture;
import es.jllopez.neis.domain.model.ContentRecord;
import es.jllopez.neis.domain.port.ContentStorePort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;

/**
 * Use case: the content tree (US-11, epic #11). Storage is a
 * first-class capability: every artifact lands in a deterministic,
 * well-ordered filesystem tree mirroring the domain model, and every
 * node is backed by a database entity (US-11.4, issue #69):
 *
 * <pre>
 * &lt;content root&gt;/&lt;address id&gt;/                one folder per address (US-11.1)
 *   &lt;capture id&gt;/                             one folder per webpage capture
 *     content/&lt;task code&gt;/&lt;file&gt;              task outputs (US-11.2, US-11.3)
 *     metadata/capture.json                   fetch record
 * </pre>
 *
 * <p>Determinism: the capture id derives from the webpage URL (never
 * from run timestamps) and a content record's id derives from
 * address+capture+task+file — the same webpage and the same task
 * output always map to the same folder and the same row, so repeated
 * operations land in (and update) the same place instead of
 * accumulating timestamped copies.</p>
 */
@Service
public class ContentStoreService {

    private static final Logger log = LoggerFactory.getLogger(ContentStoreService.class);

    private final ContentStorePort store;
    private final String contentRoot;

    public ContentStoreService(ContentStorePort store,
                               @Value("${neis.mvp.work-dir:./neis-mvp}") String contentRoot) {
        this.store = store;
        this.contentRoot = contentRoot;
    }

    /**
     * The deterministic capture id of a webpage: {@code cap-} plus 12
     * hex characters of the URL's SHA-256. Same URL in, same id out.
     */
    public String captureId(String url) {
        return "cap-" + sha256(url).substring(0, 12);
    }

    /** One folder per registered address (US-11.1). */
    public Path addressDir(Address address) {
        return ensureDir(Path.of(contentRoot, address.id()));
    }

    /**
     * The capture folder of a webpage of the address, creating and
     * recording the capture (idempotently) and its fetch record under
     * {@code metadata/}.
     */
    public Path captureDir(Address address, String url) {
        String captureId = captureId(url);
        if (store.findCapture(address.id(), captureId).isEmpty()) {
            store.saveCapture(new Capture(address.id(), captureId, url, Instant.now()));
            log.info("Content store: capture {} of {} recorded ({})",
                    captureId, address.id(), url);
        }
        Path dir = ensureDir(addressDir(address).resolve(captureId));
        writeFetchRecord(dir, address, url, captureId);
        return dir;
    }

    /**
     * Writes one task output as content (US-11.2/US-11.3): under the
     * capture's {@code content/<task code>/} folder, recorded in the
     * database with a deterministic id. Overwrites the previous
     * output of the same task over the same webpage and updates its
     * record — history belongs to the repository (US-14), not to
     * duplicated folders.
     */
    public Path writeTaskOutput(Address address, String url, String taskCode,
                                String kind, String fileName, String content) {
        Path captureDir = captureDir(address, url);
        Path taskDir = ensureDir(captureDir.resolve("content").resolve(taskCode));
        Path file = taskDir.resolve(fileName);
        try {
            Files.writeString(file, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Cannot write task output " + fileName + " for " + taskCode, e);
        }
        String recordId = "CONT-" + sha256(address.id() + "|" + captureId(url)
                + "|" + taskCode + "|" + fileName).substring(0, 12);
        store.saveContent(new ContentRecord(recordId, address.id(), captureId(url),
                taskCode, kind, file.toString(), Instant.now()));
        return file;
    }

    /** Every content record, newest first. */
    public List<ContentRecord> listAll() {
        return store.findAll();
    }

    /** Every content record of one address, newest first. */
    public List<ContentRecord> listByAddress(String addressId) {
        return store.findByAddress(addressId);
    }

    /** The content root (the tree's top; also the chain work dir). */
    public String root() {
        return contentRoot;
    }

    private void writeFetchRecord(Path captureDir, Address address, String url,
                                  String captureId) {
        Path metadataDir = ensureDir(captureDir.resolve("metadata"));
        Path record = metadataDir.resolve("capture.json");
        String json = "{\n"
                + "  \"addressId\": \"" + address.id() + "\",\n"
                + "  \"captureId\": \"" + captureId + "\",\n"
                + "  \"url\": \"" + url.replace("\"", "\\\"") + "\"\n"
                + "}\n";
        try {
            Files.writeString(record, json, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot write the fetch record for " + captureId, e);
        }
    }

    private Path ensureDir(Path dir) {
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot create content dir " + dir, e);
        }
        return dir;
    }

    private static String sha256(String input) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
