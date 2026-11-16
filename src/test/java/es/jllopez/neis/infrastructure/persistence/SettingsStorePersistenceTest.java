package es.jllopez.neis.infrastructure.persistence;

import com.zaxxer.hikari.HikariDataSource;
import es.jllopez.neis.domain.port.SettingsStorePort;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The persisted configuration overrides (V13, US-16.5 edit path issue
 * #97) survive a restart: upsert, read back, clear — on the SQLite
 * file database, like every store.
 */
@SpringBootTest
class SettingsStorePersistenceTest {

    @TempDir
    static Path tempDir;

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> "jdbc:sqlite:" + tempDir.resolve("settings.db"));
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
    private SettingsStorePort store;

    @Test
    void overridesUpsertReadBackAndClear() {
        store.save("neis.scrape.connector", "live");
        assertEquals("live", store.find("neis.scrape.connector").orElseThrow());

        // Upsert: the same key updates in place.
        store.save("neis.scrape.connector", "static");
        assertEquals("static", store.find("neis.scrape.connector").orElseThrow());

        // A second key lands alongside; all() carries both.
        store.save("neis.extraction.max-items", "500");
        assertEquals(Map.of("neis.scrape.connector", "static",
                        "neis.extraction.max-items", "500"),
                store.all());

        // Clear: delete is a no-op when absent.
        store.delete("neis.scrape.connector");
        assertTrue(store.find("neis.scrape.connector").isEmpty());
        assertEquals("500", store.find("neis.extraction.max-items").orElseThrow());
        store.delete("neis.scrape.connector"); // no-op, no throw
    }
}
