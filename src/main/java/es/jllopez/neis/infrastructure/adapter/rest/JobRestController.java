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
        if (status == null || status.isBlank()) {
            return jobs.jobs(null);
        }
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
}
