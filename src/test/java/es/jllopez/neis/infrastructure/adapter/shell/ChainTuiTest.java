package es.jllopez.neis.infrastructure.adapter.shell;

import es.jllopez.neis.application.ChainService;
import es.jllopez.neis.domain.model.Chain;
import es.jllopez.neis.domain.model.ChainTask;
import es.jllopez.neis.testsupport.InMemoryChainStorePort;
import es.jllopez.neis.testsupport.InMemoryExecutionStorePort;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The chain-management TUI (US-10.1, issue #39): a scripted console
 * drives the screen loop — list, create (step syntax), edit (rename,
 * add/remove/move steps, save/cancel), delete (explicit confirm),
 * loud refusals that leave the catalog untouched, clean EOF exit.
 */
class ChainTuiTest {

    private final InMemoryChainStorePort port = new InMemoryChainStorePort();
    private final ChainService chains = new ChainService(port,
            new InMemoryExecutionStorePort(List.of(
                    "WEBSCRAPE", "SIMPLIFY", "ANALYZE", "FILTER", "PRINT")));

    /** Scripted console: queued inputs in, captured lines out. */
    private static final class ScriptedConsole implements ConsoleIO {
        private final Deque<String> inputs = new ArrayDeque<>();
        private final List<String> output = new ArrayList<>();

        ScriptedConsole(String... inputs) {
            for (String input : inputs) {
                this.inputs.add(input);
            }
        }

        @Override
        public String readLine(String prompt) {
            return inputs.poll();
        }

        @Override
        public void println(String line) {
            output.add(line);
        }

        String transcript() {
            return String.join("\n", output);
        }

        boolean fedAllInputs() {
            return inputs.isEmpty();
        }
    }

    private void seedPipelineChain() {
        chains.create("pipeline", "the core chain", List.of(
                ChainTask.at(1, "WEBSCRAPE"),
                ChainTask.at(2, "SIMPLIFY"),
                ChainTask.at(3, "PRINT")));
    }

    // ---- the screen ----

    @Test
    void listShowsChainsWithTheirStepsThenQuits() {
        seedPipelineChain();
        ScriptedConsole io = new ScriptedConsole("l", "q");

        new ChainTui(chains, null, io).run();

        String transcript = io.transcript();
        assertTrue(transcript.contains("pipeline"));
        assertTrue(transcript.contains("WEBSCRAPE -> SIMPLIFY -> PRINT"),
                "the task list renders as ordered codes: " + transcript);
        assertTrue(io.fedAllInputs(), "the loop consumed the whole script");
    }

    @Test
    void eofExitsCleanlyWithoutBye() {
        ScriptedConsole io = new ScriptedConsole();

        new ChainTui(chains, null, io).run();

        assertFalse(io.transcript().contains("Bye."),
                "EOF (input ended) is not a user quit");
        assertTrue(io.transcript().contains("(input ended)"));
    }

    @Test
    void anUnknownCommandIsReportedWithoutTouchingTheCatalog() {
        ScriptedConsole io = new ScriptedConsole("x", "q");

        new ChainTui(chains, null, io).run();

        assertTrue(io.transcript().contains("Unknown command 'x'"));
        assertTrue(chains.list().isEmpty());
    }

    // ---- create ----

    @Test
    void createBuildsAChainFromTheStepSyntax() {
        ScriptedConsole io = new ScriptedConsole(
                "c",
                "mine",
                "a description",
                "WEBSCRAPE,SIMPLIFY:variant=lean,PRINT:max-items=5",
                "q");

        new ChainTui(chains, null, io).run();

        Chain chain = chains.show(chains.list().get(0).id());
        assertEquals("mine", chain.name());
        assertEquals(3, chain.tasks().size());
        assertEquals("WEBSCRAPE", chain.tasks().get(0).taskCode());
        assertEquals(1, chain.tasks().get(0).position());
        assertEquals("lean", chain.tasks().get(1).parameters().get("variant"));
        assertEquals("5", chain.tasks().get(2).parameters().get("max-items"));
    }

    @Test
    void aBlankNameRefusesWithoutCreating() {
        ScriptedConsole io = new ScriptedConsole(
                "c", "   ", "q");

        new ChainTui(chains, null, io).run();

        assertTrue(io.transcript().contains("Refused: a chain needs a name"));
        assertTrue(chains.list().isEmpty(), "nothing was created");
    }

    @Test
    void anUnknownTaskTypeIsRefusedAndNothingIsCreated() {
        ScriptedConsole io = new ScriptedConsole(
                "c",
                "mine",
                "",
                "WEBSCRAPE,NOTATYPE",
                "q");

        new ChainTui(chains, null, io).run();

        assertTrue(io.transcript().contains("Unknown task type"));
        assertTrue(chains.list().isEmpty(), "the refusal left no chain behind");
    }

    @Test
    void aDuplicateNameIsRefusedAtCreate() {
        seedPipelineChain();
        ScriptedConsole io = new ScriptedConsole(
                "c",
                "pipeline",
                "",
                "PRINT",
                "q");

        new ChainTui(chains, null, io).run();

        assertTrue(io.transcript().contains("already exists"));
        assertEquals(1, chains.list().size(), "still only the original chain");
    }

    // ---- edit ----

    @Test
    void editReordersStepsAndSaves() {
        seedPipelineChain();
        // edit -> move step 3 (PRINT) to position 1 -> save
        ScriptedConsole io = new ScriptedConsole(
                "e", "1",
                "m", "3", "1",
                "s",
                "q");

        new ChainTui(chains, null, io).run();

        Chain chain = chains.list().get(0);
        assertEquals("PRINT", chain.tasks().get(0).taskCode(),
                "PRINT moved to the head of the chain");
        assertEquals("WEBSCRAPE", chain.tasks().get(1).taskCode());
        assertEquals("SIMPLIFY", chain.tasks().get(2).taskCode());
        // positions renumbered 1..n in list order (the service's rule)
        assertEquals(1, chain.tasks().get(0).position());
        assertEquals(3, chain.tasks().get(2).position());
    }

    @Test
    void editRemovesAStepAndRenumbersOnSave() {
        seedPipelineChain();
        ScriptedConsole io = new ScriptedConsole(
                "e", "1",
                "r", "2",
                "s",
                "q");

        new ChainTui(chains, null, io).run();

        Chain chain = chains.list().get(0);
        assertEquals(2, chain.tasks().size());
        assertEquals("WEBSCRAPE", chain.tasks().get(0).taskCode());
        assertEquals("PRINT", chain.tasks().get(1).taskCode());
        assertEquals(2, chain.tasks().get(1).position(),
                "positions renumbered after the removal");
    }

    @Test
    void editAppendsStepsWithParameters() {
        seedPipelineChain();
        ScriptedConsole io = new ScriptedConsole(
                "e", "1",
                "a", "ANALYZE,FILTER",
                "s",
                "q");

        new ChainTui(chains, null, io).run();

        Chain chain = chains.list().get(0);
        assertEquals(5, chain.tasks().size());
        assertEquals("ANALYZE", chain.tasks().get(3).taskCode());
        assertEquals("FILTER", chain.tasks().get(4).taskCode());
    }

    @Test
    void cancelDiscardsTheWorkingCopy() {
        seedPipelineChain();
        ScriptedConsole io = new ScriptedConsole(
                "e", "1",
                "r", "1",
                "c",
                "q");

        new ChainTui(chains, null, io).run();

        Chain chain = chains.list().get(0);
        assertEquals(3, chain.tasks().size(),
                "the removal was cancelled — the chain is untouched");
        assertTrue(io.transcript().contains("Edit cancelled"));
    }

    @Test
    void aRenameOntoAnExistingNameIsRefusedAtSave() {
        seedPipelineChain();
        chains.create("other", null, List.of(ChainTask.at(1, "PRINT")));
        // edit the PIPELINE chain (listed by name: 'other' < 'pipeline',
        // so pipeline is #2), rename to 'other', save -> refused, nothing saved
        ScriptedConsole io = new ScriptedConsole(
                "e", "2",
                "n", "other",
                "s",
                "q");

        new ChainTui(chains, null, io).run();

        assertTrue(io.transcript().contains("Refused:"));
        assertEquals("pipeline", chains.list().stream()
                        .filter(c -> c.id().startsWith("CHAIN-"))
                        .filter(c -> c.tasks().size() == 3).findFirst().orElseThrow()
                        .name(),
                "the original chain kept its name");
    }

    // ---- delete ----

    @Test
    void deleteRemovesAfterExplicitConfirmation() {
        seedPipelineChain();
        ScriptedConsole io = new ScriptedConsole(
                "d", "1", "y",
                "q");

        new ChainTui(chains, null, io).run();

        assertTrue(chains.list().isEmpty());
        assertTrue(io.transcript().contains("Deleted 'pipeline'"));
    }

    @Test
    void deleteWithoutYKeepsTheChain() {
        seedPipelineChain();
        ScriptedConsole io = new ScriptedConsole(
                "d", "1", "n",
                "q");

        new ChainTui(chains, null, io).run();

        assertEquals(1, chains.list().size(), "not deleted without 'y'");
        assertTrue(io.transcript().contains("Not deleted"));
    }

    @Test
    void deleteOnAnEmptyCatalogIsRefused() {
        ScriptedConsole io = new ScriptedConsole("d", "q");

        new ChainTui(chains, null, io).run();

        assertTrue(io.transcript().contains("Nothing to pick"));
    }

    // ---- nested re-entry ----

    @Test
    void nestedQuitDoesNotPrintBye() {
        ScriptedConsole io = new ScriptedConsole("q");

        new ChainTui(chains, null, io, true).run();

        assertFalse(io.transcript().contains("Bye."),
                "nested (management-screen) quit means 'back', not exit");
    }
}
