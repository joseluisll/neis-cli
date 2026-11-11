package es.jllopez.neis.infrastructure.persistence;

import es.jllopez.neis.domain.model.Chain;
import es.jllopez.neis.domain.model.ChainTask;
import es.jllopez.neis.domain.port.ChainStorePort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Chain definitions persist (US-08.1, issue #33): the store port
 * round-trips chains — ordered tasks and per-task parameters
 * (US-08.3, issue #35) — against the SQLite file database, the name
 * is unique, and the task_code FK is enforced.
 */
@SpringBootTest
class ChainStorePersistenceTest {

    @TempDir
    static Path tempDir;

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> "jdbc:sqlite:" + tempDir.resolve("chains.db"));
    }

    @Autowired
    private ChainStorePort store;

    private Chain chain(String id, String name) {
        return new Chain(id, name, "a test chain",
                Instant.parse("2026-11-11T00:00:00Z"),
                List.of(ChainTask.at(1, "WEBSCRAPE"),
                        new ChainTask(2, "PRINT", Map.of("max-items", "10"))));
    }

    @Test
    void chainDefinitionRoundTripsWithTasksAndParameters() {
        store.save(chain("CHAIN-RT-1", "round trip"));

        Chain reloaded = store.findById("CHAIN-RT-1").orElseThrow();
        assertEquals("round trip", reloaded.name());
        assertEquals("a test chain", reloaded.description());
        assertEquals(Instant.parse("2026-11-11T00:00:00Z"), reloaded.createdAt());
        assertEquals(List.of("WEBSCRAPE", "PRINT"),
                reloaded.tasks().stream().map(ChainTask::taskCode).toList());
        assertEquals(Map.of("max-items", "10"),
                reloaded.tasks().get(1).parameters());
        assertEquals(Map.of(), reloaded.tasks().get(0).parameters());

        assertTrue(store.findByName("round trip").orElseThrow().id().equals("CHAIN-RT-1"));
        assertTrue(store.findAll().stream().anyMatch(c -> c.id().equals("CHAIN-RT-1")));
    }

    @Test
    void savingAChainReplacesItsTaskRows() {
        store.save(chain("CHAIN-UPD", "updated chain"));
        Chain v2 = new Chain("CHAIN-UPD", "updated chain", null,
                Instant.parse("2026-11-11T00:00:00Z"),
                List.of(ChainTask.at(1, "SIMPLIFY"),
                        ChainTask.at(2, "ANALYZE"),
                        ChainTask.at(3, "PRINT")));
        store.save(v2);

        Chain reloaded = store.findById("CHAIN-UPD").orElseThrow();
        assertEquals(List.of("SIMPLIFY", "ANALYZE", "PRINT"),
                reloaded.tasks().stream().map(ChainTask::taskCode).toList(),
                "the old task rows must be gone, not merged");
    }

    @Test
    void aDuplicateNameIsRefused() {
        store.save(chain("CHAIN-DUP-1", "shared name"));
        assertThrows(RuntimeException.class,
                () -> store.save(chain("CHAIN-DUP-2", "shared name")));
    }

    @Test
    void anUnknownTaskCodeIsRefusedByTheForeignKey() {
        // The domain's vocabulary check lives in ChainService; the store
        // port itself relies on the chain_tasks FK to task_types as the
        // database's last line of defense.
        Chain bad = new Chain("CHAIN-BAD", "bad fk",
                null, Instant.now(), List.of(ChainTask.at(1, "NOT_A_TASK")));
        assertThrows(RuntimeException.class, () -> store.save(bad));
    }
}
