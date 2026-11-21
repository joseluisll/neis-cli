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

    /** The single-item prune request (US-14.13): the canonical id and
     * the recorded reason — the CLI 'news prune' semantics. */
    public record PruneRequest(String itemId, String reason) {}

    /**
     * The single-item prune over REST (US-14.13): a soft delete of one
     * logical record (every row sharing the canonical id), reason
     * recorded, provenance stays readable, re-extraction never
     * resurrects. The id arrives in the request BODY, so an unknown id
     * is a caller error → 400 (never a path-404).
     */
    @PostMapping("/prune")
    public java.util.Map<String, Object> prune(
            @org.springframework.web.bind.annotation.RequestBody
            PruneRequest request) {
        if (request == null || request.itemId() == null
                || request.itemId().isBlank()) {
            throw new IllegalArgumentException(
                    "itemId is required — the canonical id (cn-…, from "
                            + "GET /api/news)");
        }
        String id = request.itemId().trim();
        if (news.item(id).isEmpty()) {
            throw new IllegalArgumentException("Unknown item id '" + id
                    + "' — canonical ids look like 'cn-xxxxxxxx' (see "
                    + "GET /api/news)");
        }
        int rows = news.prune(id, request.reason());
        java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("pruned", rows);
        out.put("itemId", id);
        out.put("reason", request.reason());
        return out;
    }

    /** The bulk-prune request (US-14.13): the SAME filter fields the
     * listing accepts (address, kind, date range, terms), a
     * completeness flag, an explicit canonical-id list, the recorded
     * reason and the confirmation flag. */
    public record BulkPruneRequest(String addressId, String kind,
            String dateFrom, String dateTo, String terms,
            String completeness, java.util.List<String> canonicalIds,
            String reason, Boolean confirmed) {}

    /**
     * The bulk prune over REST (US-14.13): dry-run by default —
     * confirmed:false (or absent) returns the matching records and
     * writes NOTHING; confirmed:true soft-deletes every matched record
     * as a whole canonical group. A zero-match selection is a no-op
     * success, not an error.
     */
    @PostMapping("/prune-bulk")
    public java.util.Map<String, Object> pruneBulk(
            @org.springframework.web.bind.annotation.RequestBody
            BulkPruneRequest request) {
        if (request == null) {
            throw new IllegalArgumentException(
                    "A request body is required (use {} for an "
                            + "explicit-canonical-ids-only selection)");
        }
        boolean confirmed = Boolean.TRUE.equals(request.confirmed());
        var selection = new es.jllopez.neis.domain.model.BulkPruneSelection(
                request.addressId(), request.kind(), request.dateFrom(),
                request.dateTo(), request.terms(), request.completeness(),
                request.canonicalIds(), request.reason());
        java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("confirmed", confirmed);
        if (!confirmed) {
            var preview = news.bulkPrunePreview(selection);
            out.put("dryRun", true);
            out.put("matchedRecords", preview.matchedRecords());
            out.put("items", preview.items());
            out.put("unknownCanonicalIds", preview.unknownCanonicalIds());
            out.put("message", "Dry run — nothing pruned; re-send with "
                    + "\"confirmed\": true to prune these records");
            return out;
        }
        var outcome = news.bulkPrune(selection);
        out.put("dryRun", false);
        out.put("prunedRecords", outcome.prunedRecords());
        out.put("prunedRows", outcome.prunedRows());
        out.put("unknownCanonicalIds", outcome.unknownCanonicalIds());
        return out;
    }
}
