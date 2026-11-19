package es.jllopez.neis.infrastructure;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * US-02.5 (issue #130): SQLITE_BUSY must not lose execution
 * transitions. The production Hikari pool pins a 15s busy_timeout and
 * WAL journal mode as connection properties (a multi-statement
 * connection-init-sql does NOT apply — probed live, US-18.4); this
 * test boots the REAL pool against a file-backed database and pins
 * the properties' take-effect AND the busy-timeout behavior under
 * genuine concurrent access — in-memory doubles cannot reproduce
 * SQLite file locking, which is why this test needs the file.
 *
 * <p>What pins WHAT (review round 1, probe-verified): the PRAGMA
 * assertions are the WAL pin (journal_mode=wal reaching the pool);
 * the writer test is the busy_timeout pin. The reader test is a
 * SMOKE CHECK only: a writer holding an INSERT transaction stays in
 * RESERVED phase, which does not block readers even in
 * rollback-journal mode, so its elapsed bound cannot catch a WAL
 * regression — it documents that routine reader/writer overlap does
 * not error out under the production configuration.</p>
 *
 * <p>The contender writer runs on a DAEMON thread (the executor-pool
 * rule: a non-daemon worker would hang the surefire JVM at exit).</p>
 */
@SpringBootTest
class SqliteConcurrencyTest {

    private static final Duration DRIVER_DEFAULT_BUDGET = Duration.ofSeconds(3);
    private static Path dbFile;

    @Autowired
    private DataSource dataSource;

    @BeforeAll
    static void createFileBackedDatabase() throws IOException {
        dbFile = Files.createTempFile("neis-us025-concurrency", ".db");
    }

    @DynamicPropertySource
    static void datasourceUrl(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> "jdbc:sqlite:" + dbFile.toAbsolutePath());
    }

    @AfterAll
    static void deleteDatabaseArtifacts() throws IOException {
        for (String suffix : new String[]{"", "-wal", "-shm"}) {
            Files.deleteIfExists(Path.of(dbFile + suffix));
        }
    }

    private static void exec(Statement statement, String sql) throws Exception {
        statement.execute(sql);
    }

    @Test
    void productionPragmasTakeEffectOnPooledConnections() throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement pragma = connection.createStatement()) {
            // the properties are applied per connection by sqlite-jdbc;
            // probe them the same way the live incident was probed
            try (var rs = pragma.executeQuery("PRAGMA busy_timeout")) {
                assertTrue(rs.next());
                assertEquals(15000, rs.getInt(1),
                        "busy_timeout must match application.yml");
            }
            try (var rs = pragma.executeQuery("PRAGMA journal_mode")) {
                assertTrue(rs.next());
                assertEquals("wal", rs.getString(1).toLowerCase(),
                        "journal_mode must be WAL (application.yml)");
            }
        }
    }

    private void createProbeTableCommitted() throws Exception {
        // the probe table must EXIST and be committed before the holder
        // opens its transaction: an uncommitted CREATE TABLE is invisible
        // to other connections (that is the whole point of WAL)
        try (Connection setup = dataSource.getConnection();
             Statement s = setup.createStatement()) {
            exec(s, "CREATE TABLE IF NOT EXISTS us025_probe("
                    + "id INTEGER PRIMARY KEY, v TEXT)");
        }
    }

    @Test
    void writerWaitsOutALongHeldLockInsteadOfFailingWithSqliteBusy()
            throws Exception {
        createProbeTableCommitted();
        ExecutorService contenderPool = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "us025-contender");
            t.setDaemon(true);
            return t;
        });
        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            try (Statement s = holder.createStatement()) {
                exec(s, "INSERT INTO us025_probe(v) VALUES ('held')");
            }
            // the contender starts while the holder's write transaction
            // is open, and must WAIT (not fail) — the lock is held past
            // the driver's default 3s budget on purpose, so only the
            // configured 15s busy_timeout lets it through
            Future<Integer> contender = contenderPool.submit(() -> {
                try (Connection c = dataSource.getConnection();
                     Statement s = c.createStatement()) {
                    return s.executeUpdate(
                            "INSERT INTO us025_probe(v) VALUES ('contender')");
                }
            });
            Thread.sleep(DRIVER_DEFAULT_BUDGET.plusSeconds(1).toMillis());
            holder.commit();
            assertEquals(1, contender.get(30, TimeUnit.SECONDS),
                    "the contender write must succeed after the lock "
                            + "releases — a SQLITE_BUSY here means the "
                            + "busy_timeout connection property is gone");
        } finally {
            contenderPool.shutdownNow();
        }
    }

    @Test
    void readerProceedsWhileAWriterHoldsItsTransaction() throws Exception {
        createProbeTableCommitted();
        // SMOKE CHECK (review round 1): NOT a WAL discriminator — a
        // writer holding an INSERT transaction stays in RESERVED phase,
        // which does not block readers even in rollback-journal mode.
        // The WAL pin lives in productionPragmasTakeEffectOnPooledConnections.
        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            try (Statement s = holder.createStatement()) {
                exec(s, "INSERT INTO us025_probe(v) VALUES ('held')");
            }
            // WAL: under the production configuration this SELECT
            // proceeds while the writer's transaction is open — routine
            // reader/writer overlap must not error out or stall.
            // The reader sees the COMMITTED state — the holder's
            // uncommitted row is invisible — so only the timely
            // completion is asserted, not the row content.
            Instant start = Instant.now();
            try (Connection reader = dataSource.getConnection();
                 Statement s = reader.createStatement();
                 var rs = s.executeQuery("SELECT COUNT(*) FROM us025_probe")) {
                assertTrue(rs.next());
                assertTrue(rs.getInt(1) >= 0);
            }
            assertTrue(Duration.between(start, Instant.now())
                            .compareTo(Duration.ofSeconds(2)) < 0,
                    "a WAL reader must proceed during the writer's "
                            + "transaction — it blocked, so journal_mode "
                            + "WAL is not taking effect");
            holder.rollback();
        }
    }
}
