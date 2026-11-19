package es.jllopez.neis.testsupport;

import es.jllopez.neis.domain.model.JobStatus;
import es.jllopez.neis.domain.model.RemoteJob;
import es.jllopez.neis.domain.port.JobStorePort;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Test double of {@link JobStorePort}: a concurrent map — the job
 * runner thread saves while the caller thread polls, so (same lesson
 * as the execution-store double) it must be a JMM-safe structure.
 * Ordering follows the port contract: newest first, by submission
 * time, nulls last (the SQLite connector orders its query the same
 * way).
 */
public class InMemoryJobStorePort implements JobStorePort {

    private final Map<String, RemoteJob> jobs = new ConcurrentHashMap<>();

    @Override
    public RemoteJob save(RemoteJob job) {
        jobs.put(job.id(), job);
        return job;
    }

    @Override
    public Optional<RemoteJob> findById(String id) {
        return Optional.ofNullable(jobs.get(id));
    }

    @Override
    public boolean saveIfStatus(String jobId, JobStatus expectedStatus,
                                RemoteJob record) {
        // the CAS, same atomicity semantics as the connector's
        // conditional UPDATE (the double cannot prove the SQL, but the
        // SERVICE contract — re-read on false — is what tests pin)
        synchronized (this) {
            RemoteJob current = jobs.get(jobId);
            if (current == null || current.status() != expectedStatus) {
                return false;
            }
            jobs.put(jobId, record);
            return true;
        }
    }

    @Override
    public List<RemoteJob> findAll() {
        return jobs.values().stream()
                .sorted(Comparator.comparing(RemoteJob::submittedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
    }
}
