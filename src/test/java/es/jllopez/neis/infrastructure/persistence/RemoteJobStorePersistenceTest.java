package es.jllopez.neis.infrastructure.persistence;

import com.zaxxer.hikari.HikariDataSource;
import es.jllopez.neis.application.AddressCatalogService;
import es.jllopez.neis.application.RemoteJobService;
import es.jllopez.neis.domain.model.Address;
import es.jllopez.neis.domain.model.AddressType;
import es.jllopez.neis.domain.model.JobStatus;
import es.jllopez.neis.domain.model.RemoteJob;
import es.jllopez.neis.domain.model.TaskExecution;
import es.jllopez.neis.domain.model.TriggerType;
import es.jllopez.neis.domain.model.VerificationStatus;
import es.jllopez.neis.domain.port.ExecutionStorePort;
import es.jllopez.neis.domain.port.JobStorePort;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Remote jobs persist and the model runs END TO END over the real
 * SQLite file store (US-18.2, issue #117): the store port round-trips
 * records, and a remote WEBSCRAPE job submitted over a created,
 * verified address reaches a terminal state, links its execution
 * record, and that record carries the REMOTE trigger — the job truly
 * wraps the execution layer (the in-memory unit test doubles cannot
 * prove the production binding; same lesson as the US-11 review).
 */
@SpringBootTest
class RemoteJobStorePersistenceTest {

    @TempDir
    static Path tempDir;

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> "jdbc:sqlite:" + tempDir.resolve("remotejobs.db"));
    }

    /**
     * US-02.4 (issue #98): the Hikari pool holds the SQLite file open
     * after the test class — release it BEFORE the @TempDir deletion.
     */
    @AfterAll
    static void releaseDatabaseFile(@Autowired DataSource dataSource) {
        if (dataSource instanceof HikariDataSource pool && !pool.isClosed()) {
            pool.close();
        }
    }

    @Autowired
    private JobStorePort jobs;

    @Autowired
    private RemoteJobService remoteJobs;

    @Autowired
    private AddressCatalogService catalog;

    @Autowired
    private ExecutionStorePort executions;

    @Test
    void jobRecordRoundTripsWithParametersAndTimestamps() {
        RemoteJob job = new RemoteJob("JOB-roundtrip", "run-task", "ADDR-001",
                Map.of("task-code", "WEBSCRAPE", "max-items", "5"),
                JobStatus.RUNNING, Instant.parse("2026-11-19T10:00:00Z"),
                Instant.parse("2026-11-19T10:00:01Z"), null,
                "EXEC-1", null, null);

        jobs.save(job);
        RemoteJob back = jobs.findById("JOB-roundtrip").orElseThrow();

        assertEquals(job, back);
        // newest first by submission time — assert ROW IDENTITY of the
        // two pinned jobs' relative order, never absolute indexes: the
        // store is class-global and the end-to-end test in this class
        // submits its own jobs into the same DB (the US-14.7 lesson)
        jobs.save(new RemoteJob("JOB-older", "run-chain",
                "ADDR-001", Map.of(), JobStatus.QUEUED,
                Instant.parse("2026-11-19T09:00:00Z"), null, null, null, null, null));
        List<RemoteJob> all = jobs.findAll();
        assertTrue(all.indexOf(all.stream()
                        .filter(j -> j.id().equals("JOB-roundtrip")).findFirst().orElseThrow())
                < all.indexOf(all.stream()
                        .filter(j -> j.id().equals("JOB-older")).findFirst().orElseThrow()),
                "JOB-roundtrip (submitted later) must be listed before JOB-older");
    }

    @Test
    void remoteJobRunsEndToEndOverTheRealStoreAndLinksItsExecution()
            throws InterruptedException {
        Address address = catalog.create("ADDR-JOBTEST", "Remote job test",
                "created by RemoteJobStorePersistenceTest",
                "https://remote-job-test.example.eu/news", AddressType.URL_NEWS);
        catalog.updateVerification(address.id(), VerificationStatus.VERIFIED,
                "2026-11-19T00:00:00Z", "persistence-test");

        RemoteJob submitted = remoteJobs.submitRunTask("WEBSCRAPE",
                address.id(), null);
        RemoteJob finished = awaitTerminal(submitted.id());

        assertTrue(finished.isTerminal());
        assertNotNull(finished.executionId());
        TaskExecution execution = executions.findById(finished.executionId())
                .orElseThrow();
        // the wrapped run is REMOTE-triggered and its lifecycle matches
        assertEquals(TriggerType.REMOTE, execution.triggerType());
        assertEquals(
                execution.status() == es.jllopez.neis.domain.model.ExecutionStatus.SUCCESS
                        ? JobStatus.COMPLETED : JobStatus.FAILED,
                finished.status());
        if (finished.status() == JobStatus.FAILED) {
            assertNotNull(finished.error());
        } else {
            assertNotNull(finished.result());
        }
    }

    private RemoteJob awaitTerminal(String jobId) throws InterruptedException {
        for (int i = 0; i < 300; i++) {
            Optional<RemoteJob> job = remoteJobs.job(jobId);
            if (job.isPresent() && job.get().isTerminal()) {
                return job.get();
            }
            Thread.sleep(200);
        }
        throw new AssertionError("Job " + jobId + " did not reach a terminal state");
    }
}
