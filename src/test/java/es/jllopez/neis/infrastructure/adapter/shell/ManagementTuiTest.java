package es.jllopez.neis.infrastructure.adapter.shell;

import es.jllopez.neis.application.AddressCatalogService;
import es.jllopez.neis.application.AiAssistantService;
import es.jllopez.neis.application.ChainService;
import es.jllopez.neis.application.ContentStoreService;
import es.jllopez.neis.application.ExecutionService;
import es.jllopez.neis.application.ManagementInfoService;
import es.jllopez.neis.application.NewsEventService;
import es.jllopez.neis.application.PreconfiguredFilterProperties;
import es.jllopez.neis.application.PreconfiguredFilters;
import es.jllopez.neis.application.ScheduleProperties;
import es.jllopez.neis.application.TaskWorkService;
import es.jllopez.neis.domain.model.Address;
import es.jllopez.neis.domain.model.AddressType;
import es.jllopez.neis.domain.model.ChainTask;
import es.jllopez.neis.domain.model.NewsEventItem;
import es.jllopez.neis.domain.model.TriggerType;
import es.jllopez.neis.domain.model.VerificationStatus;
import es.jllopez.neis.domain.service.UrlSyntaxValidator;
import es.jllopez.neis.domain.port.SelectorStorePort;
import es.jllopez.neis.testsupport.InMemoryAddressCatalogPort;
import es.jllopez.neis.testsupport.InMemoryChainStorePort;
import es.jllopez.neis.testsupport.InMemoryContentStorePort;
import es.jllopez.neis.testsupport.InMemoryExecutionStorePort;
import es.jllopez.neis.testsupport.InMemoryNewsEventStorePort;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The unified management interface (US-16, issues #77-#82): a scripted
 * console drives the sections — the dashboard aggregates (US-16.2),
 * execution history, detail and re-run (US-16.3), repository browsing
 * with the item detail and the content tree (US-16.4), the
 * configuration overview (US-16.5) and the re-entry into the address
 * screen (US-16.1/US-09.1). Clean EOF exit at every level.
 */
class ManagementTuiTest {

    private static final Executor DIRECT = Runnable::run;

    private final InMemoryAddressCatalogPort catalogPort =
            new InMemoryAddressCatalogPort(List.of(
                    new Address("ADDR-001", "News", "https://example.eu/news",
                            AddressType.URL_NEWS)));
    private final AddressCatalogService catalog =
            new AddressCatalogService(catalogPort, new UrlSyntaxValidator());
    private final InMemoryExecutionStorePort executionStore =
            new InMemoryExecutionStorePort(List.of("CHAIN", "WEBSCRAPE", "SIMPLIFY",
                    "ANALYZE", "FILTER", "PRINT"));
    private final InMemoryChainStorePort chainStore = new InMemoryChainStorePort();
    private final ChainService chains =
            new ChainService(chainStore, executionStore);
    private final InMemoryNewsEventStorePort newsStore = new InMemoryNewsEventStorePort();
    private final NewsEventService newsEvents = new NewsEventService(newsStore,
            executionStore, new ContentStoreService(new InMemoryContentStorePort(), "work"));
    private final ExecutionService executions;

    ManagementTuiTest() {
        TaskWorkService work = mock(TaskWorkService.class);
        when(work.execute(anyString(), any(Address.class))).thenReturn("done");
        executions = new ExecutionService(executionStore, chainStore, catalog,
                new UrlSyntaxValidator(), work, DIRECT, 5);
    }

    private ManagementInfoService info() {
        return new ManagementInfoService(catalog, chains, executions, newsEvents,
                new ScheduleProperties(List.of()), mock(AiAssistantService.class));
    }

    /** A management screen over the real in-memory services, with a
     * recorded outcome thread so re-run evidence is observable. */
    private ManagementTui tui(ConsoleIO io) {
        TaskWorkService work = mock(TaskWorkService.class);
        when(work.execute(anyString(), any(Address.class), any(), any(), any(), any()))
                .thenReturn("PRINT: ok");
        ExecutionService realExecutions = new ExecutionService(executionStore,
                chainStore, catalog, new UrlSyntaxValidator(), work, DIRECT, 5);
        ManagementInfoService info = new ManagementInfoService(catalog, chains,
                realExecutions, newsEvents, new ScheduleProperties(List.of()),
                mock(AiAssistantService.class));
        return new ManagementTui(catalog, chains, realExecutions, newsEvents,
                new ContentStoreService(new InMemoryContentStorePort(), "work"),
                info, new ScheduleProperties(List.of()),
                mock(AiAssistantService.class), mock(SelectorStorePort.class), io);
    }

    private void seedVerifiedAddress() {
        catalog.updateVerification("ADDR-001", VerificationStatus.VERIFIED,
                "2026-11-16T10:00:00Z", null);
    }

    private void seedChain() {
        chains.create("news-pipeline", "test chain",
                List.of(new ChainTask(1, "WEBSCRAPE", java.util.Map.of())));
    }

    private void seedExecution() {
        seedVerifiedAddress();
        executions.submit("WEBSCRAPE", "ADDR-001", TriggerType.MANUAL);
    }

    private void seedNews() {
        newsStore.saveAll("ADDR-001", "news", List.of(
                new NewsEventItem("ADDR-001", "news", "A title",
                        "A description", "https://example.eu/a", "2026-11-10")),
                null, null);
    }

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

    @Test
    void mainScreenShowsTheDashboardAggregatesThenQuits() {
        seedVerifiedAddress();
        seedChain();
        seedNews();
        seedExecution();
        ScriptedConsole io = new ScriptedConsole("d", "q");

        tui(io).run();

        String transcript = io.transcript();
        assertTrue(transcript.contains("DASHBOARD"), transcript);
        assertTrue(transcript.contains("1 registered (1 verified"), transcript);
        assertTrue(transcript.contains("Chains         : 1"), transcript);
        assertTrue(transcript.contains("Repository     : 1 news/events"), transcript);
        assertTrue(transcript.contains("WEBSCRAPE"), transcript);
        assertTrue(transcript.contains("SUCCESS"), transcript);
        assertTrue(io.fedAllInputs());
    }

    @Test
    void unknownSectionIsRefusedAndTheScreenContinues() {
        ScriptedConsole io = new ScriptedConsole("zzz", "q");

        tui(io).run();

        assertTrue(io.transcript().contains("Unknown section 'zzz'"), io.transcript());
        assertTrue(io.fedAllInputs());
    }

    @Test
    void executionSectionListsRecordsShowsDetailAndReRuns() {
        seedExecution();
        int recordsBefore = executions.list().size();
        ScriptedConsole io = new ScriptedConsole(
                "e",
                "1",                        // detail of the newest record
                "r", "1",                   // re-run of that record
                "b",
                "q");

        tui(io).run();

        String transcript = io.transcript();
        assertTrue(transcript.contains("EXECUTIONS (US-16.3)"), transcript);
        assertTrue(transcript.contains("Execution EXEC-"), transcript);
        assertTrue(transcript.contains("Re-submitted"), transcript);
        assertEquals(recordsBefore + 1, executions.list().size(),
                "the re-run leaves its own execution record");
        assertTrue(io.fedAllInputs());
    }

    @Test
    void reRunWithAnOutOfRangeNumberIsRefused() {
        seedExecution();
        int recordsBefore = executions.list().size();
        ScriptedConsole io = new ScriptedConsole(
                "e", "r", "99", "b", "q");

        tui(io).run();

        assertTrue(io.transcript().contains("Refused"), io.transcript());
        assertEquals(recordsBefore, executions.list().size(), "nothing re-run");
    }

    @Test
    void chainSectionShowsDefinitionsAndRunsOneOverAnAddress() {
        seedVerifiedAddress();
        seedChain();
        ScriptedConsole io = new ScriptedConsole(
                "c",
                "1",                        // chain detail
                "r", "1", "ADDR-001", "y",  // run chain 1 over ADDR-001, wait
                "b",
                "q");

        tui(io).run();

        String transcript = io.transcript();
        assertTrue(transcript.contains("CHAINS"), transcript);
        assertTrue(transcript.contains("news-pipeline"), transcript);
        assertTrue(transcript.contains("Submitted EXEC-"), transcript);
        assertTrue(transcript.contains("SUCCESS"), transcript);
    }

    @Test
    void repositorySectionListsItemsShowsDetailAndTheContentTree() {
        seedNews();
        ScriptedConsole io = new ScriptedConsole(
                "r",
                "1",                        // item detail
                "t",                        // the storage tree
                "b",
                "q");

        tui(io).run();

        String transcript = io.transcript();
        assertTrue(transcript.contains("REPOSITORY (US-16.4)"), transcript);
        assertTrue(transcript.contains("A title"), transcript);
        assertTrue(transcript.contains("News/event item"), transcript);
        assertTrue(transcript.contains("complete"), transcript);
        assertTrue(transcript.contains("Content tree"), transcript);
        assertTrue(transcript.contains("<no stored content yet>"), transcript);
    }

    @Test
    void repositoryItemDetailShowsMissingMandatoryFields() {
        newsStore.saveAll("ADDR-001", "news", List.of(
                new NewsEventItem("ADDR-001", "news", "Only a title",
                        null, null, null)),
                null, null);
        ScriptedConsole io = new ScriptedConsole("r", "1", "b", "q");

        tui(io).run();

        String transcript = io.transcript();
        assertTrue(transcript.contains("INCOMPLETE"), transcript);
        assertTrue(transcript.contains("description, date"), transcript);
    }

    @Test
    void configSectionShowsTheConfigurationOverviewAndRefusesUnknownAddresses() {
        seedVerifiedAddress();
        ScriptedConsole io = new ScriptedConsole(
                "g",
                "s", "ADDR-999",            // unknown address -> loud refusal
                "s", "ADDR-001",            // no stored selectors -> note
                "b",
                "q");

        tui(io).run();

        String transcript = io.transcript();
        assertTrue(transcript.contains("CONFIGURATION (US-16.5)"), transcript);
        assertTrue(transcript.contains("Work dir"), transcript);
        assertTrue(transcript.contains("Schedules (0)"), transcript);
        assertTrue(transcript.contains("Unknown address") || transcript.contains("ADDR-999"),
                transcript);
        assertTrue(transcript.contains("No stored selectors for ADDR-001"), transcript);
    }

    @Test
    void addressSectionReEntersTheAddressScreenAndReturnsToTheMenu() {
        seedVerifiedAddress();
        ScriptedConsole io = new ScriptedConsole(
                "a",                        // into the address screen
                "q",                        // back out of it (address screen's own quit)
                "q");                       // quit the management screen

        tui(io).run();

        String transcript = io.transcript();
        assertTrue(transcript.contains("Address management (US-09.1)"), transcript);
        assertTrue(transcript.contains("ADDR-001"), transcript);
        // back at the management level afterwards: the section menu shows
        assertTrue(transcript.contains("Sections: [d]ashboard"), transcript);
    }

    @Test
    void eofAtTheMainMenuExitsCleanly() {
        ScriptedConsole io = new ScriptedConsole();

        tui(io).run();

        assertTrue(io.transcript().contains("(input ended)"), io.transcript());
    }

    @Test
    void eofInsideASectionReturnsToTheMainMenu() {
        ScriptedConsole io = new ScriptedConsole("e");

        tui(io).run();

        // the executions section printed, then EOF returned to the main
        // screen loop, which then hit EOF too and exited cleanly
        assertTrue(io.transcript().contains("EXECUTIONS (US-16.3)"), io.transcript());
        assertTrue(io.transcript().contains("(input ended)"), io.transcript());
    }
}
