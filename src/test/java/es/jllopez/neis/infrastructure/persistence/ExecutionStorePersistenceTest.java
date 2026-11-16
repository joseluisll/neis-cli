package es.jllopez.neis.infrastructure.persistence;

import com.zaxxer.hikari.HikariDataSource;
import es.jllopez.neis.domain.model.ExecutionStatus;
import es.jllopez.neis.domain.model.TaskExecution;
import es.jllopez.neis.domain.model.TriggerType;
import es.jllopez.neis.domain.model.Chain;
import es.jllopez.neis.domain.model.ChainTask;
import es.jllopez.neis.domain.port.ChainStorePort;
import es.jllopez.neis.domain.port.ExecutionStorePort;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import javax.sql.DataSource;
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

    /**
     * US-02.4 (issue #98): the Hikari pool holds the SQLite file open
     * after the test class, so on Windows JUnit's @TempDir cleanup
     * cannot delete it and the extension teardown errors (invisible on
     * Linux, where unlink-while-open succeeds). Closing the pool here
     * releases the file BEFORE the @TempDir deletion — JUnit runs
     * @AfterAll methods before extension cleanup. The context is
     * per-class (its datasource URL points at this class's temp dir),
     * so closing the pool disturbs no other test class.
     */
    @AfterAll
    static void releaseDatabaseFile(@Autowired DataSource dataSource) {
        if (dataSource instanceof HikariDataSource pool && !pool.isClosed()) {
            pool.close();
        }
    }

    @Autowired
    private ExecutionStorePort store;

    @Autowired
    private ChainStorePort chainStore;

    @Autowired
    private TaskTypeJpaRepository taskTypes;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private TaskExecution record(String id, ExecutionStatus status, String outcome) {
        return new TaskExecution(id, "CHAIN", "ADDR-001", "CHAIN-RT",
                TriggerType.MANUAL,
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
        // The execution's address and chain must exist: the FKs are enforced.
        jdbcTemplate.update(
                "INSERT INTO addresses (id, name, url, type, verification_status, "
                        + "created_at, updated_at) VALUES ('ADDR-001', 'Fixture', "
                        + "'https://example.org/x', 'URL_NEWS', 'UNVERIFIED', "
                        + "'2026-11-11T00:00:00Z', '2026-11-11T00:00:00Z')");
        chainStore.save(new Chain("CHAIN-RT", "fixture chain", null,
                Instant.parse("2026-11-11T00:00:00Z"),
                List.of(ChainTask.at(1, "WEBSCRAPE"))));
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
        assertEquals("CHAIN-RT", reloaded.chainId(),
                "the record's chain link must round-trip (US-08)");
    }

    @Test
    void parametersRoundTripThroughTheV12Column() {
        // The execution's address must exist: the FK is enforced.
        // (OR IGNORE: the sibling test on the shared class DB may have
        // inserted ADDR-001 already.)
        jdbcTemplate.update(
                "INSERT OR IGNORE INTO addresses (id, name, url, type, verification_status, "
                        + "created_at, updated_at) VALUES ('ADDR-001', 'Fixture', "
                        + "'https://example.org/x', 'URL_NEWS', 'UNVERIFIED', "
                        + "'2026-11-11T00:00:00Z', '2026-11-11T00:00:00Z')");
        // US-16.3 review finding #97: the run's input parameters are part
        // of the record — persisted through V12 so the run detail shows
        // what the run was given and a re-run can be faithful.
        TaskExecution withParams = new TaskExecution("EXEC-RT-PARAMS", "WEBSCRAPE",
                "ADDR-001", null, TriggerType.MANUAL, ExecutionStatus.PENDING,
                Instant.parse("2026-11-11T12:00:00Z"), null, null,
                java.util.Map.of("selector", "ul.items > li", "max-items", "5"));
        store.save(withParams);

        // A lifecycle save WITHOUT parameters must not wipe the stored map:
        // the RUNNING transition carries the same map (with() threads it).
        store.save(store.findById("EXEC-RT-PARAMS").orElseThrow()
                .with(ExecutionStatus.RUNNING, null, null));

        TaskExecution reloaded = store.findById("EXEC-RT-PARAMS").orElseThrow();
        assertEquals(java.util.Map.of("selector", "ul.items > li", "max-items", "5"),
                reloaded.parameters(), "the parameters must round-trip (V12 JSON)");

        // A run without parameters reads back as an empty map (and stays
        // NULL in the column, matching the pre-V12 rows).
        TaskExecution bare = new TaskExecution("EXEC-RT-BARE", "WEBSCRAPE",
                "ADDR-001", null, TriggerType.MANUAL, ExecutionStatus.PENDING,
                Instant.parse("2026-11-11T12:01:00Z"), null, null);
        store.save(bare);
        TaskExecution bareReloaded = store.findById("EXEC-RT-BARE").orElseThrow();
        assertTrue(bareReloaded.parameters().isEmpty(), "no parameters -> empty map");
        assertEquals(1, jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM executions WHERE id = 'EXEC-RT-BARE' "
                                + "AND parameters IS NULL", Integer.class),
                "the bare record's parameters column stays NULL");
    }

    @Test
    void unknownTaskCodeIsRefusedByTheForeignKey() {
        org.junit.jupiter.api.Assertions.assertThrows(
                org.springframework.orm.jpa.JpaSystemException.class,
                () -> store.save(new TaskExecution("EXEC-BAD", "NOT_A_TASK", "ADDR-001",
                        null, TriggerType.MANUAL, ExecutionStatus.PENDING,
                        Instant.now(), null, null)));
    }
}
