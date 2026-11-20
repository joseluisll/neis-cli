package es.jllopez.neis.infrastructure.adapter.rest;

import es.jllopez.neis.infrastructure.adapter.ConditionalOnServerEnabled;

import es.jllopez.neis.application.ContentStoreService;
import es.jllopez.neis.domain.model.ContentRecord;
import es.jllopez.neis.domain.model.RemoteJob;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Content-store queries over REST (US-18.1, issue #116): the captured
 * artifacts' records (the artifacts themselves live in the content
 * root; reading their bytes is US-18.8's export slice).
 */
@RestController
@RequestMapping("/api/content")
@ConditionalOnServerEnabled
public class ContentRestController {

    private final ContentStoreService content;
    private final es.jllopez.neis.application.RemoteJobService jobs;

    public ContentRestController(ContentStoreService content,
                                 es.jllopez.neis.application.RemoteJobService jobs) {
        this.content = content;
        this.jobs = jobs;
    }

    @GetMapping
    public List<ContentRecord> list(
            @RequestParam(name = "addressId", required = false) String addressId) {
        return addressId == null || addressId.isBlank()
                ? content.listAll() : content.listByAddress(addressId.trim());
    }

    @GetMapping("/{id}")
    public ContentRecord show(@PathVariable String id) {
        return content.byId(id).orElseThrow(() ->
                new java.util.NoSuchElementException("Unknown content record: " + id));
    }

    /** The content root and the records grouped per address — the
     * storage tree (US-11.4) in one payload. */
    @GetMapping("/tree")
    public TreeView tree() {
        List<ContentRecord> records = content.listAll();
        return new TreeView(content.root(), records);
    }

    public record TreeView(String root, List<ContentRecord> records) {
    }

    /**
     * Submits a content export as a remote job (US-18.8, issue #126):
     * 202 with the job id (the US-18.2 model); the finished job's
     * result carries the archive metadata and the download path
     * ({@code /api/exports/<token>}) — never inline bytes.
     */
    @PostMapping("/export")
    @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.ACCEPTED)
    public java.util.Map<String, String> export(
            @RequestBody ExportRequest request) {
        RemoteJob job = jobs.submitExport(request.addressId(),
                request.executionId());
        return java.util.Map.of(
                "jobId", job.id(),
                "operation", job.operation(),
                "status", job.status().name());
    }

    public record ExportRequest(String addressId, String executionId) {
    }
}
