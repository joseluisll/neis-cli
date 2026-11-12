package es.jllopez.neis.infrastructure.adapter.scrape;

import es.jllopez.neis.domain.model.FilterDefinition;
import es.jllopez.neis.domain.model.StoredSelectors;
import es.jllopez.neis.domain.port.SelectorStorePort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
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

    // Minimal, dependency-free JSON for the record's fields: seven
    // scalars plus the filter catalogue (array of {name, values[]}).

    private String toJson(StoredSelectors s) {
        return """
                {
                  "addressId": "%s",
                  "kind": "%s",
                  "selector": "%s",
                  "filterSelector": "%s",
                  "discoveredBy": "%s",
                  "discoveredAt": "%s",
                  "notes": "%s"%s
                }
                """.formatted(esc(s.addressId()), esc(s.kind()), esc(s.selector()),
                esc(s.filterSelector()), esc(s.discoveredBy()), esc(s.discoveredAt()),
                esc(s.notes()), filtersJson(s.filters()));
    }

    /** The filter catalogue as a JSON array (empty string when none). */
    private String filtersJson(List<FilterDefinition> filters) {
        if (filters == null || filters.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(",\n  \"filters\": [");
        for (int i = 0; i < filters.size(); i++) {
            FilterDefinition f = filters.get(i);
            if (i > 0) {
                out.append(',');
            }
            out.append("\n    {\"name\": \"").append(esc(f.name())).append("\", \"values\": [");
            for (int j = 0; j < f.values().size(); j++) {
                if (j > 0) {
                    out.append(", ");
                }
                out.append('"').append(esc(f.values().get(j))).append('"');
            }
            out.append("]}");
        }
        out.append("\n  ]");
        return out.toString();
    }

    private StoredSelectors parse(String json) {
        return new StoredSelectors(
                field(json, "addressId"), field(json, "kind"), field(json, "selector"),
                field(json, "filterSelector"),
                parseFilters(json),
                field(json, "discoveredBy"), field(json, "discoveredAt"),
                field(json, "notes"));
    }

    /** Lenient scan of the "filters" array: objects of {name, values[]}. */
    private List<FilterDefinition> parseFilters(String json) {
        List<FilterDefinition> filters = new ArrayList<>();
        int arrayStart = json.indexOf("\"filters\"");
        if (arrayStart < 0) {
            return filters;
        }
        arrayStart = json.indexOf('[', arrayStart);
        if (arrayStart < 0) {
            return filters;
        }
        int depth = 0;
        int objectStart = -1;
        for (int i = arrayStart + 1; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '{') {
                if (depth == 0) {
                    objectStart = i;
                }
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0 && objectStart >= 0) {
                    String object = json.substring(objectStart, i + 1);
                    String name = field(object, "name");
                    if (name != null) {
                        filters.add(new FilterDefinition(name, valuesArray(object)));
                    }
                    objectStart = -1;
                }
            } else if (c == ']' && depth == 0) {
                break;
            }
        }
        return filters;
    }

    /** Flat string array of a "values" key: ["a","b"] -> [a, b]. */
    private List<String> valuesArray(String object) {
        List<String> values = new ArrayList<>();
        var m = java.util.regex.Pattern.compile("\"values\"\\s*:\\s*\\[(.*?)\\]",
                java.util.regex.Pattern.DOTALL).matcher(object);
        if (!m.find()) {
            return values;
        }
        for (String raw : m.group(1).split("\",\\s*\"")) {
            String value = raw.replaceFirst("^\"+", "").replaceFirst("\"+$", "")
                    .replace("\\\"", "\"").trim();
            if (!value.isEmpty()) {
                values.add(value);
            }
        }
        return values;
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
