package es.jllopez.neis.domain.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The chain model (US-08.1, issue #33): an ordered task list, max 10
 * elements, positions 1..n without gaps.
 */
class ChainTest {

    private ChainTask step(int position, String code) {
        return ChainTask.at(position, code);
    }

    @Test
    void tasksAreKeptInPositionOrderRegardlessOfListOrder() {
        Chain chain = new Chain("CHAIN-1", "reordered", null,
                Instant.parse("2026-11-11T00:00:00Z"),
                List.of(step(2, "SIMPLIFY"), step(1, "WEBSCRAPE")));
        assertEquals(List.of("WEBSCRAPE", "SIMPLIFY"),
                chain.tasks().stream().map(ChainTask::taskCode).toList());
        assertEquals(List.of(1, 2),
                chain.tasks().stream().map(ChainTask::position).toList());
    }

    @Test
    void anEmptyChainIsRefused() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new Chain("CHAIN-1", "empty", null, Instant.now(), List.of()));
        assertTrue(e.getMessage().contains("at least one"));
    }

    @Test
    void moreThanTenTasksIsRefused() {
        List<ChainTask> eleven = new java.util.ArrayList<>();
        for (int i = 1; i <= Chain.MAX_TASKS; i++) {
            eleven.add(step(i, "WEBSCRAPE"));
        }
        eleven.add(step(1, "PRINT")); // an 11th element, reusing a position
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new Chain("CHAIN-1", "too long", null, Instant.now(), eleven));
        assertTrue(e.getMessage().contains("at most 10"));
    }

    @Test
    void exactlyTenTasksIsAccepted() {
        List<ChainTask> ten = new java.util.ArrayList<>();
        for (int i = 1; i <= Chain.MAX_TASKS; i++) {
            ten.add(step(i, "WEBSCRAPE"));
        }
        Chain chain = new Chain("CHAIN-1", "full", null, Instant.now(), ten);
        assertEquals(Chain.MAX_TASKS, chain.tasks().size());
    }

    @Test
    void positionGapsAreRefused() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new Chain("CHAIN-1", "gapped", null, Instant.now(),
                        List.of(step(1, "WEBSCRAPE"), step(3, "PRINT"))));
        assertTrue(e.getMessage().contains("positions must be 1..n"));
    }

    @Test
    void aBlankNameIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> new Chain("CHAIN-1", "  ", null, Instant.now(),
                        List.of(step(1, "PRINT"))));
    }

    @Test
    void aStepWithoutATaskCodeIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> new ChainTask(1, "  ", Map.of()));
    }

    @Test
    void aStepPositionOutsideOneToTenIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> new ChainTask(0, "PRINT", Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new ChainTask(11, "PRINT", Map.of()));
    }

    @Test
    void nullParametersDefaultToAnEmptyMap() {
        ChainTask task = new ChainTask(1, "PRINT", null);
        assertTrue(task.parameters().isEmpty());
    }

    @Test
    void aStepPositionAtTheMaximumIsAccepted() {
        assertEquals(Chain.MAX_TASKS, new ChainTask(Chain.MAX_TASKS, "PRINT", null).position());
    }
}
