package es.jllopez.neis.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import es.jllopez.neis.domain.model.NewsEventItem;
import es.jllopez.neis.domain.model.NewsEventQuery;
import es.jllopez.neis.domain.port.NewsEventStorePort;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Use case: exporting the news/events repository (US-14.6, issue #84)
 * to JSON or CSV, filterable with the same criteria as the query
 * capability (US-14.3: kind, address, date range) plus completeness
 * (US-14.4). Exported records carry the mandatory metadata, the
 * completeness flags and the US-14.2 provenance.
 *
 * <p>Determinism (acceptance criterion): the record set is sorted by
 * the rows' natural identity (address, then detail URL — or title) —
 * never by write timestamps — and JSON fields keep a fixed order, so
 * the same data with the same filters produces byte-identical files.
 * The default output file is overwritten in place, which keeps the
 * export repeatable.</p>
 */
@Service
public class NewsEventExportService {

    /** The completeness filter of an export (US-14.4 flags). */
    public enum Completeness {
        /** Every record, complete or not (the default). */
        ALL,
        /** Only records with every mandatory field. */
        COMPLETE,
        /** Only records flagged incomplete, with their missing fields. */
        INCOMPLETE
    }

    /** One completed export: where it was written and how much it has. */
    public record ExportResult(Path file, int records, String format) {}

    private static final ObjectMapper JSON = new ObjectMapper();

    /** CSV header — the fixed column order of every CSV export. */
    private static final String[] CSV_COLUMNS = {
            "address_id", "kind", "title", "description", "date", "url",
            "picture", "location", "incomplete", "missing_fields",
            "canonical_id", "execution_id", "content_id"};

    private final NewsEventStorePort newsEventStore;

    public NewsEventExportService(NewsEventStorePort newsEventStore) {
        this.newsEventStore = newsEventStore;
    }

    /**
     * Exports the records matching the query (US-14.3 filters; the
     * completeness filter narrows by the US-14.4 flag) to {@code out}
     * as JSON or CSV.
     */
    public ExportResult export(NewsEventQuery query, Completeness completeness,
                               String format, Path out) {
        String fmt = normalizeFormat(format);
        List<NewsEventItem> items = select(query, completeness);
        try {
            Path file = "json".equals(fmt)
                    ? writeJson(items, out)
                    : writeCsv(items, out);
            return new ExportResult(file, items.size(), fmt);
        } catch (IOException e) {
            // No 'Export failed' prefix here: the driving adapter owns
            // the user-facing failure message (US-14.6 re-review — the
            // doubled prefix survived a first fix attempt).
            throw new UncheckedIOException("Failed to write the export to " + out, e);
        }
    }

    private List<NewsEventItem> select(NewsEventQuery query,
                                       Completeness completeness) {
        // The export is unbounded: Integer.MAX_VALUE is not a real
        // limit (the connector fetches everything matching).
        List<NewsEventItem> items = new ArrayList<>(newsEventStore.search(
                new NewsEventQuery(query.addressId(), query.kind(),
                        query.dateFrom(), query.dateTo(), Integer.MAX_VALUE)));
        if (completeness == Completeness.COMPLETE) {
            items.removeIf(NewsEventItem::incomplete);
        } else if (completeness == Completeness.INCOMPLETE) {
            items.removeIf(item -> !item.incomplete());
        }
        // Deterministic order: the rows' natural identity, never the
        // write timestamps (US-14.6 acceptance criterion).
        items.sort(Comparator
                .comparing(NewsEventItem::addressId)
                .thenComparing(item -> item.url() != null ? item.url()
                        : "title:" + item.title()));
        return items;
    }

    private Path writeJson(List<NewsEventItem> items, Path out)
            throws IOException {
        List<Map<String, Object>> records = new ArrayList<>();
        for (NewsEventItem item : items) {
            // LinkedHashMap: fixed field order, deterministic bytes.
            Map<String, Object> record = new LinkedHashMap<>();
            record.put("address_id", item.addressId());
            record.put("kind", item.kind());
            record.put("title", item.title());
            record.put("description", item.description());
            record.put("date", item.date());
            record.put("url", item.url());
            record.put("picture", item.picture());
            record.put("location", item.location());
            record.put("incomplete", item.incomplete());
            record.put("missing_fields", item.missingFields());
            record.put("canonical_id", item.canonicalId());
            record.put("execution_id", item.executionId());
            record.put("content_id", item.contentId());
            records.add(record);
        }
        Files.write(out, JSON.writerWithDefaultPrettyPrinter()
                .writeValueAsBytes(records));
        return out;
    }

    private Path writeCsv(List<NewsEventItem> items, Path out)
            throws IOException {
        StringBuilder csv = new StringBuilder();
        csv.append(String.join(",", CSV_COLUMNS)).append('\n');
        for (NewsEventItem item : items) {
            String[] values = {
                    item.addressId(), item.kind(), item.title(),
                    item.description(), item.date(), item.url(),
                    item.picture(), item.location(),
                    String.valueOf(item.incomplete()), item.missingFields(),
                    item.canonicalId(), item.executionId(), item.contentId()};
            for (int i = 0; i < values.length; i++) {
                if (i > 0) {
                    csv.append(',');
                }
                csv.append(quote(values[i]));
            }
            csv.append('\n');
        }
        Files.write(out, csv.toString().getBytes(StandardCharsets.UTF_8));
        return out;
    }

    /** RFC 4180 quoting: every field quoted, embedded quotes doubled,
     * LF line endings. A SQL NULL and an empty string both render as
     * an empty quoted field — JSON preserves the distinction. */
    private static String quote(String value) {
        return "\"" + (value == null ? "" : value.replace("\"", "\"\""))
                + "\"";
    }

    private static String normalizeFormat(String format) {
        String fmt = format == null ? ""
                : format.trim().toLowerCase(Locale.ROOT);
        if (!"json".equals(fmt) && !"csv".equals(fmt)) {
            throw new IllegalArgumentException(
                    "Unsupported export format '" + format
                            + "' — use json or csv");
        }
        return fmt;
    }
}
