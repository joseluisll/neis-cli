package es.jllopez.neis.infrastructure.adapter.rest;

import es.jllopez.neis.infrastructure.adapter.ConditionalOnServerEnabled;

import es.jllopez.neis.application.RemoteJobService;
import es.jllopez.neis.application.ExecutionService;
import es.jllopez.neis.domain.model.RemoteJob;
import es.jllopez.neis.domain.model.TaskExecution;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Execution queries and the ASYNCHRONOUS run submissions over REST
 * (US-18.1 + US-18.2, issues #116/#117): a run returns 202 with a job
 * id immediately — never blocks on completion; the caller polls
 * {@code /api/jobs/{id}}.
 */
@RestController
@RequestMapping("/api/executions")
@ConditionalOnServerEnabled
public class ExecutionRestController {

    private final ExecutionService executions;
    private final RemoteJobService jobs;

    public ExecutionRestController(ExecutionService executions,
                                   RemoteJobService jobs) {
        this.executions = executions;
        this.jobs = jobs;
    }

    public record RunRequest(String taskCode, String addressId,
                             Map<String, String> parameters) {
    }

    public record RunAccepted(String jobId, String operation,
                              String status) {
    }

    @PostMapping("/run")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public RunAccepted runTask(@RequestBody RunRequest request) {
        if (request.taskCode() == null || request.addressId() == null) {
            throw new IllegalArgumentException(
                    "taskCode and addressId are required");
        }
        RemoteJob job = jobs.submitRunTask(request.taskCode(),
                request.addressId(), request.parameters());
        return new RunAccepted(job.id(), job.operation(),
                job.status().name());
    }

    @GetMapping
    public List<TaskExecution> list(
            @RequestParam(name = "limit", defaultValue = "20") int limit) {
        List<TaskExecution> all = executions.list();
        return all.subList(0, Math.min(Math.max(limit, 0), all.size()));
    }

    @GetMapping("/{id}")
    public TaskExecution show(@PathVariable String id) {
        return PathLookup.or404(() -> executions.show(id));
    }

    @GetMapping("/types")
    public List<String> types() {
        return executions.taskTypes();
    }
}
