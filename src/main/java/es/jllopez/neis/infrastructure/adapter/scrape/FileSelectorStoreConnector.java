package es.jllopez.neis.infrastructure.adapter.scrape;

import es.jllopez.neis.domain.model.StoredSelectors;
import es.jllopez.neis.domain.port.SelectorStorePort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Connector (driven adapter) persisting the validated working selectors
 * per address as one JSON file per address under
 * {@code <work-dir>/selectors/<addressId>.json} (MVP issue #90; the full
 * persistence layer US-02/#2 replaces it behind the same port).
 *
 * <p>Hand-rolled minimal JSON (six scalar fields) — the full persistence
 * layer US-02/#2 replaces this connector behind the same port.</p>
 */
@Component
public class FileSelectorStoreConnector implements SelectorStorePort {

    private final Path storeDir;

    public FileSelectorStoreConnector(
            @Value("${neis.core.work-dir:./core-work}") String workDir) {
        this.storeDir = Path.of(workDir, "selectors");
    }

    @Override
    public Optional<StoredSelectors> load(String addressId) {
        Path file = storeDir.resolve(addressId + ".json");
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(parse(Files.readString(file, StandardCharsets.UTF_8)));
        } catch (IOException | RuntimeException e) {
            return Optional.empty(); // unreadable/corrupt entry -> re-identify
        }
    }

    @Override
    public void save(StoredSelectors selectors) {
        try {
            Files.createDirectories(storeDir);
            Files.writeString(storeDir.resolve(selectors.addressId() + ".json"),
                    toJson(selectors), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Cannot persist selector for " + selectors.addressId(), e);
        }
    }

    // Minimal, dependency-free JSON for the scalar fields of the record.

    private String toJson(StoredSelectors s) {
        return """
                {
                  "addressId": "%s",
                  "kind": "%s",
                  "selector": "%s",
                  "filterSelector": "%s",
                  "discoveredBy": "%s",
                  "discoveredAt": "%s",
                  "notes": "%s"
                }
                """.formatted(esc(s.addressId()), esc(s.kind()), esc(s.selector()),
                esc(s.filterSelector()), esc(s.discoveredBy()), esc(s.discoveredAt()),
                esc(s.notes()));
    }

    private StoredSelectors parse(String json) {
        return new StoredSelectors(
                field(json, "addressId"), field(json, "kind"), field(json, "selector"),
                field(json, "filterSelector"),
                field(json, "discoveredBy"), field(json, "discoveredAt"),
                field(json, "notes"));
    }

    private String field(String json, String name) {
        var m = java.util.regex.Pattern
                .compile("\"" + name + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
                .matcher(json);
        return m.find() ? m.group(1).replace("\\\"", "\"").replace("\\\\", "\\") : null;
    }

    private String esc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n");
    }
}
