package es.jllopez.neis.infrastructure.persistence;

import es.jllopez.neis.domain.model.ExecutionStatus;
import es.jllopez.neis.domain.model.TaskExecution;
import es.jllopez.neis.domain.model.TriggerType;
import es.jllopez.neis.domain.port.ExecutionStorePort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Execution records persist (US-05.3, issue #26): the store port
 * round-trips records against the SQLite file database, the task-type
 * vocabulary is seeded, and the task_code/address FKs are enforced.
 */
@SpringBootTest
class ExecutionStorePersistenceTest {

    @TempDir
    static Path tempDir;

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> "jdbc:sqlite:" + tempDir.resolve("executions.db"));
    }

    @Autowired
    private ExecutionStorePort store;

    @Autowired
    private TaskTypeJpaRepository taskTypes;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private TaskExecution record(String id, ExecutionStatus status, String outcome) {
        return new TaskExecution(id, "CHAIN", "ADDR-001", TriggerType.MANUAL,
                status, Instant.parse("2026-11-11T12:00:00Z"),
                status == ExecutionStatus.SUCCESS || status == ExecutionStatus.FAILED
                        ? Instant.parse("2026-11-11T12:00:05Z") : null,
                outcome);
    }

    @Test
    void taskTypeVocabularyIsSeededIdempotently() {
        List<String> codes = store.taskTypes();
        assertTrue(codes.contains("WEBSCRAPE"));
        assertTrue(codes.contains("SIMPLIFY"));
        assertTrue(codes.contains("ANALYZE"));
        assertTrue(codes.contains("FILTER"));
        assertTrue(codes.contains("PRINT"));
        assertTrue(codes.contains("CHAIN"));
        assertEquals(codes.stream().distinct().count(), codes.size());
    }

    @Test
    void executionRecordRoundTripsAndProgresses() {
        // The execution's address must exist: the FK is enforced.
        jdbcTemplate.update(
                "INSERT INTO addresses (id, name, url, type, verification_status, "
                        + "created_at, updated_at) VALUES ('ADDR-001', 'Fixture', "
                        + "'https://example.org/x', 'URL_NEWS', 'UNVERIFIED', "
                        + "'2026-11-11T00:00:00Z', '2026-11-11T00:00:00Z')");
        store.save(record("EXEC-RT-1", ExecutionStatus.PENDING, null));

        TaskExecution running = store.findById("EXEC-RT-1").orElseThrow()
                .with(ExecutionStatus.RUNNING, null, null);
        store.save(running);
        TaskExecution done = store.findById("EXEC-RT-1").orElseThrow()
                .with(ExecutionStatus.SUCCESS, Instant.parse("2026-11-11T12:00:05Z"),
                        "chain finished: 20 items");
        store.save(done);

        TaskExecution reloaded = store.findById("EXEC-RT-1").orElseThrow();
        assertEquals(ExecutionStatus.SUCCESS, reloaded.status());
        assertNotNull(reloaded.finishedAt());
        assertEquals("chain finished: 20 items", reloaded.outcome());
        assertEquals(TriggerType.MANUAL, reloaded.triggerType());
        assertEquals("CHAIN", reloaded.taskCode());
    }

    @Test
    void unknownTaskCodeIsRefusedByTheForeignKey() {
        org.junit.jupiter.api.Assertions.assertThrows(
                org.springframework.orm.jpa.JpaSystemException.class,
                () -> store.save(new TaskExecution("EXEC-BAD", "NOT_A_TASK", "ADDR-001",
                        TriggerType.MANUAL, ExecutionStatus.PENDING,
                        Instant.now(), null, null)));
    }
}
