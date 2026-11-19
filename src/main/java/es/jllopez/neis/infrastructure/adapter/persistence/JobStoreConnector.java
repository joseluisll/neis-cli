package es.jllopez.neis.infrastructure.adapter.persistence;

import es.jllopez.neis.domain.model.JobStatus;
import es.jllopez.neis.domain.model.RemoteJob;
import es.jllopez.neis.domain.port.JobStorePort;
import es.jllopez.neis.infrastructure.persistence.RemoteJobEntity;
import es.jllopez.neis.infrastructure.persistence.RemoteJobJpaRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Driven adapter implementing {@link JobStorePort} over the Spring
 * Data JPA repositories (US-18.2, issue #117): remote jobs in the
 * SQLite {@code remote_jobs} table of schema V19, timestamps ISO-8601
 * TEXT, parameters as a JSON object — the same storage conventions as
 * the executions table.
 */
@Component
public class JobStoreConnector implements JobStorePort {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<Map<String, String>> PARAMS = new TypeReference<>() {};

    private final RemoteJobJpaRepository jobs;

    public JobStoreConnector(RemoteJobJpaRepository jobs) {
        this.jobs = jobs;
    }

    @Override
    @Transactional
    public RemoteJob save(RemoteJob job) {
        RemoteJobEntity entity = jobs.findById(job.id()).orElseGet(RemoteJobEntity::new);
        entity.setId(job.id());
        entity.setOperation(job.operation());
        entity.setAddressId(job.addressId());
        entity.setParameters(toJson(job.parameters()));
        entity.setStatus(job.status());
        entity.setSubmittedAt(job.submittedAt() == null ? null : job.submittedAt().toString());
        entity.setStartedAt(job.startedAt() == null ? null : job.startedAt().toString());
        entity.setFinishedAt(job.finishedAt() == null ? null : job.finishedAt().toString());
        entity.setExecutionId(job.executionId());
        entity.setResult(job.result());
        entity.setError(job.error());
        return toDomain(jobs.save(entity));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<RemoteJob> findById(String id) {
        return jobs.findById(id).map(this::toDomain);
    }

    @Override
    @Transactional
    public boolean saveIfStatus(String jobId, JobStatus expectedStatus,
                                RemoteJob record) {
        // the atomic compare-and-set (native conditional UPDATE — the
        // WHERE re-checks the stored status inside the same statement)
        int updated = jobs.casUpdate(jobId, expectedStatus.name(),
                record.status().name(),
                record.startedAt() == null ? null : record.startedAt().toString(),
                record.finishedAt() == null ? null : record.finishedAt().toString(),
                record.executionId(),
                record.result(),
                record.error());
        return updated > 0;
    }

    @Override
    @Transactional(readOnly = true)
    public List<RemoteJob> findAll() {
        return jobs.findAllByOrderBySubmittedAtDesc().stream()
                .map(this::toDomain).toList();
    }

    private RemoteJob toDomain(RemoteJobEntity e) {
        // A malformed stored timestamp must not break every read: a
        // single corrupt row would otherwise break findById AND
        // findAll for all jobs — degrade that one field to null
        // (corrupt-data-only; the writer always stores ISO-8601).
        return new RemoteJob(e.getId(), e.getOperation(), e.getAddressId(),
                fromJson(e.getParameters()), e.getStatus(),
                parseInstant(e.getSubmittedAt()),
                parseInstant(e.getStartedAt()),
                parseInstant(e.getFinishedAt()),
                e.getExecutionId(), e.getResult(), e.getError());
    }

    private Instant parseInstant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException corrupt) {
            return null;
        }
    }

    private String toJson(Map<String, String> parameters) {
        try {
            return JSON.writeValueAsString(parameters == null ? Map.of() : parameters);
        } catch (Exception e) {
            throw new IllegalStateException("Could not serialize job parameters", e);
        }
    }

    private Map<String, String> fromJson(String parameters) {
        if (parameters == null || parameters.isBlank()) {
            return Map.of();
        }
        try {
            return JSON.readValue(parameters, PARAMS);
        } catch (Exception e) {
            throw new IllegalStateException("Could not deserialize job parameters", e);
        }
    }
}
