package es.jllopez.neis.infrastructure.adapter.rest;

import es.jllopez.neis.infrastructure.adapter.ConditionalOnServerEnabled;

import es.jllopez.neis.application.NewsEventExportService;
import es.jllopez.neis.application.NewsEventService;
import es.jllopez.neis.domain.model.NewsEventItem;
import es.jllopez.neis.domain.model.NewsEventQuery;
import es.jllopez.neis.domain.model.NewsEventSighting;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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
        NewsEventQuery query = new NewsEventQuery(addressId, kind,
                dateFrom, dateTo, limit);
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
    public NewsEventExportService.ExportResult export(
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
        return export.export(query, level, format,
                java.nio.file.Files.createTempFile("neis-news-export-",
                        "." + ("csv".equalsIgnoreCase(format) ? "csv" : "json")));
    }
}
