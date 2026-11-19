package es.jllopez.neis.infrastructure.adapter.rest;

import es.jllopez.neis.application.ChainService;
import es.jllopez.neis.application.RemoteJobService;
import es.jllopez.neis.domain.model.Chain;
import es.jllopez.neis.domain.model.ChainTask;
import es.jllopez.neis.domain.model.RemoteJob;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Chain definition CRUD and the asynchronous chain run over REST
 * (US-18.1, issue #116). Definition bodies carry ordered steps, each
 * with its own parameters (US-08.3).
 */
@RestController
@RequestMapping("/api/chains")
@ConditionalOnServerEnabled
public class ChainRestController {

    private final ChainService chains;
    private final RemoteJobService jobs;

    public ChainRestController(ChainService chains, RemoteJobService jobs) {
        this.chains = chains;
        this.jobs = jobs;
    }

    public record StepRequest(String taskCode, Map<String, String> parameters) {
    }

    public record ChainRequest(String name, String description,
                               List<StepRequest> steps) {
    }

    public record ChainRunRequest(String addressId) {
    }

    public record RunAccepted(String jobId, String operation,
                              String status) {
    }

    @GetMapping
    public List<Chain> list() {
        return chains.list();
    }

    @GetMapping("/{id}")
    public Chain show(@PathVariable String id) {
        return chains.show(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Chain create(@RequestBody ChainRequest request) {
        return chains.create(request.name(), request.description(),
                steps(request));
    }

    @PutMapping("/{id}")
    public Chain update(@PathVariable String id,
                        @RequestBody ChainRequest request) {
        return chains.update(id, request.name(), request.description(),
                steps(request));
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable String id) {
        chains.delete(id);
    }

    /** The ASYNC chain run (US-18.2): 202 + job id, never blocks. */
    @PostMapping("/{id}/run")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public RunAccepted run(@PathVariable String id,
                           @RequestBody ChainRunRequest request) {
        if (request == null || request.addressId() == null) {
            throw new IllegalArgumentException("addressId is required");
        }
        RemoteJob job = jobs.submitRunChain(id, request.addressId());
        return new RunAccepted(job.id(), job.operation(),
                job.status().name());
    }

    private List<ChainTask> steps(ChainRequest request) {
        if (request.steps() == null) {
            return List.of();
        }
        List<ChainTask> steps = new ArrayList<>();
        for (int i = 0; i < request.steps().size(); i++) {
            StepRequest step = request.steps().get(i);
            steps.add(new ChainTask(i + 1, step.taskCode(),
                    step.parameters() == null ? Map.of() : step.parameters()));
        }
        return steps;
    }
}
