package es.jllopez.neis.infrastructure.adapter.rest;

import es.jllopez.neis.infrastructure.adapter.ConditionalOnServerEnabled;

import es.jllopez.neis.application.RemoteJobService;
import es.jllopez.neis.domain.model.JobStatus;
import es.jllopez.neis.domain.model.RemoteJob;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The remote-job polling surface (US-18.2, issue #117): every async
 * submission (runs; exports once US-18.8 lands) returns a job id, and
 * the caller polls HERE for status and result.
 */
@RestController
@RequestMapping("/api/jobs")
@ConditionalOnServerEnabled
public class JobRestController {

    private final RemoteJobService jobs;

    public JobRestController(RemoteJobService jobs) {
        this.jobs = jobs;
    }

    @GetMapping
    public List<RemoteJob> list(
            @RequestParam(name = "status", required = false) String status) {
        List<RemoteJob> records =
                status == null || status.isBlank()
                        ? jobs.jobs(null)
                        : safeJobs(status);
        // US-18.1 review finding 1: the whole-table view is
        // server-capped.
        return records.subList(0, Math.min(RestCaps.TABLE_CAP, records.size()));
    }

    private List<RemoteJob> safeJobs(String status) {
        try {
            return jobs.jobs(JobStatus.valueOf(status.trim().toUpperCase()));
        } catch (IllegalArgumentException unknown) {
            throw new IllegalArgumentException("Unknown job status: " + status
                    + " (QUEUED, RUNNING, COMPLETED, FAILED, CANCELLED)");
        }
    }

    @GetMapping("/{id}")
    public RemoteJob show(@PathVariable String id) {
        return jobs.job(id).orElseThrow(() ->
                new java.util.NoSuchElementException("Unknown job: " + id));
    }

    /**
     * Job cancellation (US-18.7, issue #122): queued jobs are cancelled
     * before their run starts; running jobs get their runner
     * interrupted (the outcome is observable in the record —
     * CANCELLED is distinct from FAILED); an already-finished job is a
     * no-op with a clear note, never an error.
     */
    @org.springframework.web.bind.annotation.PostMapping("/{id}/cancel")
    public RemoteJobService.CancellationOutcome cancel(
            @PathVariable String id) {
        return jobs.cancel(id);
    }
}
