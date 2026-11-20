package es.jllopez.neis.infrastructure.adapter.rest;

import es.jllopez.neis.infrastructure.adapter.ConditionalOnServerEnabled;

import es.jllopez.neis.application.NewsEventExportService;
import es.jllopez.neis.application.NewsEventService;
import es.jllopez.neis.domain.model.NewsEventItem;
import es.jllopez.neis.domain.model.NewsEventQuery;
import es.jllopez.neis.domain.model.NewsEventSighting;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The news/events repository over REST (US-18.1, issue #116): query,
 * free-text search, sighting history, provenance gaps and export —
 * the same use cases behind the news commands.
 */
@RestController
@RequestMapping("/api/news")
@ConditionalOnServerEnabled
public class NewsRestController {

    private final NewsEventService news;
    private final NewsEventExportService export;

    public NewsRestController(NewsEventService news,
                              NewsEventExportService export) {
        this.news = news;
        this.export = export;
    }

    @GetMapping
    public List<NewsEventItem> list(
            @RequestParam(name = "q", required = false) String text,
            @RequestParam(name = "addressId", required = false) String addressId,
            @RequestParam(name = "kind", required = false) String kind,
            @RequestParam(name = "dateFrom", required = false) String dateFrom,
            @RequestParam(name = "dateTo", required = false) String dateTo,
            @RequestParam(name = "limit", defaultValue = "20") int limit) {
        // US-18.1 review finding 1: the client cap is bounded by a
        // server cap.
        NewsEventQuery query = new NewsEventQuery(addressId, kind,
                dateFrom, dateTo, Math.min(Math.max(limit, 0), RestCaps.TABLE_CAP));
        if (text != null && !text.isBlank()) {
            return news.search(text.trim(), query);
        }
        return news.search(query);
    }

    @GetMapping("/count")
    public long count() {
        return news.count();
    }

    @GetMapping("/sightings/{canonicalId}")
    public List<NewsEventSighting> sightings(
            @PathVariable String canonicalId) {
        return news.sightings(canonicalId);
    }

    @GetMapping("/provenance-gaps")
    public List<NewsEventService.ProvenanceGap> provenanceGaps() {
        return news.provenanceGaps();
    }

    /** Export to the server's temp dir; the returned record names the
     * file, the record count and the format (US-18.8 replaces this
     * with a download endpoint). */
    @PostMapping("/export")
    public ResponseEntity<byte[]> export(
            @RequestParam(name = "format", defaultValue = "json") String format,
            @RequestParam(name = "addressId", required = false) String addressId,
            @RequestParam(name = "kind", required = false) String kind,
            @RequestParam(name = "completeness", defaultValue = "FULL")
                    String completeness) throws java.io.IOException {
        NewsEventQuery query = new NewsEventQuery(addressId, kind, null,
                null, NewsEventQuery.DEFAULT_LIMIT);
        NewsEventExportService.Completeness level =
                NewsEventExportService.Completeness.valueOf(
                        completeness.trim().toUpperCase());
        // US-18.1 review finding 2 (resolved with US-18.8's download
        // endpoint): the archive streams as the response ATTACHMENT —
        // the temp file is deleted in all cases, and the server-side
        // path is never echoed to the caller.
        String ext = "csv".equalsIgnoreCase(format) ? "csv" : "json";
        java.nio.file.Path temp = java.nio.file.Files.createTempFile(
                "neis-news-export-", "." + ext);
        try {
            export.export(query, level, format, temp);
            byte[] body = java.nio.file.Files.readAllBytes(temp);
            return ResponseEntity.ok()
                    .contentType("csv".equals(ext)
                            ? MediaType.parseMediaType("text/csv")
                            : MediaType.APPLICATION_JSON)
                    .header("Content-Disposition",
                            "attachment; filename=\"news-export." + ext
                                    + "\"")
                    .contentLength(body.length)
                    .body(body);
        } finally {
            java.nio.file.Files.deleteIfExists(temp);
        }
    }
}
