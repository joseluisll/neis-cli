package es.jllopez.neis.application;

import es.jllopez.neis.domain.model.NewsEventItem;
import es.jllopez.neis.domain.model.NewsEventQuery;
import es.jllopez.neis.testsupport.InMemoryNewsEventStorePort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.io.UncheckedIOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The repository export (US-14.6, issue #84): JSON and CSV with the
 * US-14.3 filter criteria plus the completeness filter, records
 * carrying the US-14.4 metadata/flags and US-14.2 provenance, and
 * byte-identical files for the same data and filters.
 */
class NewsEventExportServiceTest {

    @TempDir
    Path tempDir;

    private final InMemoryNewsEventStorePort store = new InMemoryNewsEventStorePort();
    private final NewsEventExportService export =
            new NewsEventExportService(store);

    private void seed() {
        store.saveAll("ADDR-1", "news", List.of(new NewsEventItem(
                "ADDR-1", "news", "Complete story", "Described",
                "https://x.eu/1", "2026-11-14")), "exec-1", "CONT-1");
        store.saveAll("ADDR-2", "event", List.of(new NewsEventItem(
                "ADDR-2", "event", "Sparse event", null,
                "https://x.eu/2", null)), "exec-2", "CONT-2");
    }

    @Test
    void jsonExportCarriesMetadataCompletenessAndProvenance() throws Exception {
        seed();
        Path out = tempDir.resolve("export.json");
        var result = export.export(NewsEventQuery.everything(),
                NewsEventExportService.Completeness.ALL, "json", out);

        assertEquals(2, result.records());
        String json = Files.readString(out, StandardCharsets.UTF_8);
        assertTrue(json.contains("\"title\" : \"Complete story\""));
        assertTrue(json.contains("\"incomplete\" : false"));
        assertTrue(json.contains("\"incomplete\" : true"));
        assertTrue(json.contains("\"missing_fields\" : \"description, date\""));
        assertTrue(json.contains("\"execution_id\" : \"exec-1\""));
        assertTrue(json.contains("\"content_id\" : \"CONT-2\""));
        assertTrue(json.contains("\"canonical_id\""));
        // US-14.9: the export carries the normalized ISO date next to
        // the raw one (the double computes it on save).
        assertTrue(json.contains("\"date_normalized\""),
                "the export exposes the machine-comparable date");
    }

    @Test
    void csvExportQuotesAndEscapesEveryField() throws Exception {
        store.saveAll("ADDR-1", "news", List.of(new NewsEventItem(
                "ADDR-1", "news", "Said \"hello\", twice",
                "Line one\nLine two", "https://x.eu/q", "2026-11-14")),
                "exec-1", "CONT-1");
        Path out = tempDir.resolve("export.csv");
        export.export(NewsEventQuery.everything(),
                NewsEventExportService.Completeness.ALL, "csv", out);

        String csv = Files.readString(out, StandardCharsets.UTF_8);
        String[] lines = csv.split("\n", -1);
        assertEquals("address_id,kind,title,description,date,date_normalized,"
                        + "url,picture,"
                        + "location,incomplete,missing_fields,canonical_id,"
                        + "execution_id,content_id",
                lines[0], "the fixed header row");
        assertTrue(csv.contains("\"Said \"\"hello\"\", twice\""),
                "embedded quotes are doubled inside quoted fields");
        // The embedded newline stays inside the quoted field, so the
        // record spans two physical lines (RFC 4180).
        assertTrue(csv.contains("\"Line one\nLine two\""),
                "embedded newlines stay inside the quoted field");
        assertEquals(3, lines.length - 1,
                "header + one record spanning two physical lines + trailing");
    }

    @Test
    void filtersNarrowTheExport() {
        seed();
        Path out = tempDir.resolve("filtered.json");

        assertEquals(1, export.export(new NewsEventQuery(
                        "ADDR-2", null, null, null, 1),
                NewsEventExportService.Completeness.ALL, "json", out).records());
        assertEquals(1, export.export(new NewsEventQuery(
                        null, "news", null, null, 1),
                NewsEventExportService.Completeness.ALL, "json", out).records());
        assertEquals(1, export.export(NewsEventQuery.everything(),
                NewsEventExportService.Completeness.INCOMPLETE, "json", out).records());
        assertEquals(1, export.export(NewsEventQuery.everything(),
                NewsEventExportService.Completeness.COMPLETE, "json", out).records());

        // Date range: the undated event is excluded while active.
        assertEquals(1, export.export(new NewsEventQuery(
                        null, null, "2026-11-01", null, 1),
                NewsEventExportService.Completeness.ALL, "json", out).records());
    }

    @Test
    void sameDataAndFiltersProduceByteIdenticalFiles() throws Exception {
        seed();
        Path first = tempDir.resolve("one.json");
        Path second = tempDir.resolve("two.json");
        Path csvFirst = tempDir.resolve("one.csv");
        Path csvSecond = tempDir.resolve("two.csv");
        var query = new NewsEventQuery(null, null, null, null, 1);

        export.export(query, NewsEventExportService.Completeness.ALL, "json", first);
        export.export(query, NewsEventExportService.Completeness.ALL, "json", second);
        export.export(query, NewsEventExportService.Completeness.ALL, "csv", csvFirst);
        export.export(query, NewsEventExportService.Completeness.ALL, "csv", csvSecond);

        org.junit.jupiter.api.Assertions.assertArrayEquals(
                Files.readAllBytes(first), Files.readAllBytes(second),
                "JSON: same data + filters -> byte-identical file");
        org.junit.jupiter.api.Assertions.assertArrayEquals(
                Files.readAllBytes(csvFirst), Files.readAllBytes(csvSecond),
                "CSV: same data + filters -> byte-identical file");
    }

    @Test
    void recordOrderIsTheNaturalIdentityNotTheWriteOrder() throws Exception {
        // ADDR-2 is written (and re-written) LAST; the export order is
        // by (address, url-or-title), so the file does not move when a
        // re-extraction refreshes a row.
        seed();
        store.saveAll("ADDR-2", "event", List.of(new NewsEventItem(
                "ADDR-2", "event", "Sparse event", null,
                "https://x.eu/2", null)), "exec-3", "CONT-3");
        Path out = tempDir.resolve("order.json");
        export.export(NewsEventQuery.everything(),
                NewsEventExportService.Completeness.ALL, "json", out);
        String json = Files.readString(out, StandardCharsets.UTF_8);
        assertTrue(json.indexOf("ADDR-1") < json.indexOf("ADDR-2"),
                "records sorted by address, not by write recency");
    }

    @Test
    void unsupportedFormatIsRejected() {
        seed();
        assertThrows(IllegalArgumentException.class, () -> export.export(
                NewsEventQuery.everything(),
                NewsEventExportService.Completeness.ALL, "xml",
                tempDir.resolve("out.xml")));
    }

    @Test
    void ioFailureMessageCarriesNoPrefixTheAdapterOwnsTheWording() {
        // US-14.6 re-review: the service must not prefix its IO failure
        // with 'Export failed' — the driving adapter owns the user-facing
        // failure message; a doubled prefix survived a first fix attempt.
        seed();
        Path missing = tempDir.resolve("no/such/dir/out.json");
        var e = assertThrows(UncheckedIOException.class, () -> export.export(
                NewsEventQuery.everything(),
                NewsEventExportService.Completeness.ALL, "json", missing));
        assertTrue(!e.getMessage().startsWith("Export failed"),
                "no CLI prefix in the service message: " + e.getMessage());
        assertTrue(e.getMessage().contains(missing.toString()));
    }
}
