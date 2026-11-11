package es.jllopez.neis.application;

import es.jllopez.neis.domain.model.Chain;
import es.jllopez.neis.domain.model.ChainTask;
import es.jllopez.neis.testsupport.InMemoryChainStorePort;
import es.jllopez.neis.testsupport.InMemoryExecutionStorePort;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Chain definition management (US-08.1, issue #33): create validates
 * the ordered 1..10 task list against the executor's vocabulary, and
 * the definitions round-trip through the store port.
 */
class ChainServiceTest {

    private ChainService service(InMemoryChainStorePort store) {
        return new ChainService(store,
                new InMemoryExecutionStorePort(List.of(
                        "WEBSCRAPE", "SIMPLIFY", "ANALYZE", "FILTER", "PRINT", "CHAIN")));
    }

    @Test
    void createNormalizesPositionsInTheGivenOrder() {
        InMemoryChainStorePort store = new InMemoryChainStorePort();
        Chain chain = service(store).create("weekly digest", "the news pipeline",
                List.of(ChainTask.at(7, "WEBSCRAPE"),
                        new ChainTask(3, "PRINT", Map.of("max-items", "10"))));

        assertEquals(2, chain.tasks().size());
        assertEquals(1, chain.tasks().get(0).position());
        assertEquals("WEBSCRAPE", chain.tasks().get(0).taskCode());
        assertEquals(2, chain.tasks().get(1).position());
        assertEquals(Map.of("max-items", "10"), chain.tasks().get(1).parameters());
        assertTrue(chain.id().startsWith("CHAIN-"));
        // The definition is retrievable by id and name.
        assertEquals(chain.id(), store.findByName("weekly digest").orElseThrow().id());
    }

    @Test
    void anUnknownTaskTypeIsRefused() {
        ChainService service = service(new InMemoryChainStorePort());
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.create("bad", null, List.of(ChainTask.at(1, "NOT_A_TASK"))));
        assertTrue(e.getMessage().contains("Unknown task type"));
    }

    @Test
    void aNestedChainStepIsRefused() {
        ChainService service = service(new InMemoryChainStorePort());
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.create("nested", null, List.of(ChainTask.at(1, "CHAIN"))));
        assertTrue(e.getMessage().contains("nested"));
    }

    @Test
    void aDuplicateNameIsRefused() {
        ChainService service = service(new InMemoryChainStorePort());
        service.create("dup", null, List.of(ChainTask.at(1, "PRINT")));
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.create("dup", null, List.of(ChainTask.at(1, "PRINT"))));
        assertTrue(e.getMessage().contains("already exists"));
    }

    @Test
    void aBlankNameIsRefused() {
        ChainService service = service(new InMemoryChainStorePort());
        assertThrows(IllegalArgumentException.class,
                () -> service.create("   ", null, List.of(ChainTask.at(1, "PRINT"))));
    }

    @Test
    void anEmptyStepListIsRefused() {
        ChainService service = service(new InMemoryChainStorePort());
        assertThrows(IllegalArgumentException.class,
                () -> service.create("empty", null, List.of()));
    }

    @Test
    void moreThanTenStepsIsRefused() {
        ChainService service = service(new InMemoryChainStorePort());
        List<ChainTask> eleven = new java.util.ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            eleven.add(ChainTask.at(i, "WEBSCRAPE"));
        }
        eleven.add(ChainTask.at(1, "PRINT")); // an 11th step
        assertThrows(IllegalArgumentException.class,
                () -> service.create("too long", null, eleven));
    }

    @Test
    void listReturnsEveryDefinitionOrderedByName() {
        InMemoryChainStorePort store = new InMemoryChainStorePort();
        ChainService service = service(store);
        service.create("zeta", null, List.of(ChainTask.at(1, "PRINT")));
        service.create("alpha", null, List.of(ChainTask.at(1, "WEBSCRAPE")));
        assertEquals(List.of("alpha", "zeta"),
                service.list().stream().map(Chain::name).toList());
    }

    @Test
    void anUnknownChainIdIsRefusedOnShow() {
        ChainService service = service(new InMemoryChainStorePort());
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.show("CHAIN-MISSING"));
        assertTrue(e.getMessage().contains("Unknown chain"));
    }
}
