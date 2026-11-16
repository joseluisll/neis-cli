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
import es.jllopez.neis.application.SettingsService;
import es.jllopez.neis.application.TaskWorkService;
import es.jllopez.neis.domain.model.Address;
import es.jllopez.neis.domain.model.AddressType;
import es.jllopez.neis.domain.model.ChainTask;
import es.jllopez.neis.domain.model.ExecutionStatus;
import es.jllopez.neis.domain.model.NewsEventItem;
import es.jllopez.neis.domain.model.TaskExecution;
import es.jllopez.neis.domain.model.TriggerType;
import es.jllopez.neis.domain.model.VerificationStatus;
import es.jllopez.neis.domain.service.UrlSyntaxValidator;
import es.jllopez.neis.domain.port.SelectorStorePort;
import es.jllopez.neis.testsupport.InMemoryAddressCatalogPort;
import es.jllopez.neis.testsupport.InMemoryChainStorePort;
import es.jllopez.neis.testsupport.InMemoryContentStorePort;
import es.jllopez.neis.testsupport.InMemoryExecutionStorePort;
import es.jllopez.neis.testsupport.InMemoryNewsEventStorePort;
import es.jllopez.neis.testsupport.InMemorySettingsStorePort;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
                mock(AiAssistantService.class), mock(SelectorStorePort.class),
                new es.jllopez.neis.application.PreconfiguredSelectorProperties(List.of()),
                new es.jllopez.neis.application.PreconfiguredFilterProperties(List.of()),
                io);
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

    @Test
    void dashboardShowsNextScheduleFireTimesAndTaskTypes() {
        seedVerifiedAddress();
        ScheduleProperties withSchedule = new ScheduleProperties(List.of(
                new ScheduleProperties.Entry("nightly-scrape", "WEBSCRAPE",
                        "ADDR-001", "0 5 3 * * *")));
        ManagementInfoService info = new ManagementInfoService(catalog, chains,
                executions, newsEvents, withSchedule, mock(AiAssistantService.class));
        ScriptedConsole io = new ScriptedConsole("d", "q");
        ManagementTui screen = new ManagementTui(catalog, chains, executions,
                newsEvents, new ContentStoreService(new InMemoryContentStorePort(), "work"),
                info, withSchedule, mock(AiAssistantService.class),
                mock(SelectorStorePort.class),
                new es.jllopez.neis.application.PreconfiguredSelectorProperties(List.of()),
                new es.jllopez.neis.application.PreconfiguredFilterProperties(List.of()),
                io);

        screen.run();

        String transcript = io.transcript();
        assertTrue(transcript.contains("nightly-scrape"), transcript);
        assertTrue(transcript.contains("next run "), transcript);
        assertTrue(transcript.contains("Task types     : "), transcript);
        assertTrue(transcript.contains("WEBSCRAPE"), transcript);
    }

    @Test
    void dashboardShowsAnInvalidCronAsAnErrorNotSilence() {
        ScheduleProperties badSchedule = new ScheduleProperties(List.of(
                new ScheduleProperties.Entry("broken", "WEBSCRAPE",
                        "ADDR-001", "not-a-cron")));
        ManagementInfoService info = new ManagementInfoService(catalog, chains,
                executions, newsEvents, badSchedule, mock(AiAssistantService.class));
        ScriptedConsole io = new ScriptedConsole("d", "q");
        ManagementTui screen = new ManagementTui(catalog, chains, executions,
                newsEvents, new ContentStoreService(new InMemoryContentStorePort(), "work"),
                info, badSchedule, mock(AiAssistantService.class),
                mock(SelectorStorePort.class),
                new es.jllopez.neis.application.PreconfiguredSelectorProperties(List.of()),
                new es.jllopez.neis.application.PreconfiguredFilterProperties(List.of()),
                io);

        screen.run();

        assertTrue(io.transcript().contains("invalid cron"), io.transcript());
    }

    @Test
    void dashboardListsRecentFailedRunsWithTheirOutcome() {
        seedVerifiedAddress();
        // a failing task: the mock TaskWorkService throws -> the record
        // goes FAILED with the cause in the outcome
        TaskWorkService failing = mock(TaskWorkService.class);
        when(failing.execute(anyString(), any(Address.class), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("boom: selector invalid"));
        ExecutionService failingExecutions = new ExecutionService(executionStore,
                chainStore, catalog, new UrlSyntaxValidator(), failing, DIRECT, 5);
        failingExecutions.submit("WEBSCRAPE", "ADDR-001", TriggerType.MANUAL);
        ManagementInfoService info = new ManagementInfoService(catalog, chains,
                failingExecutions, newsEvents,
                new ScheduleProperties(List.of()), mock(AiAssistantService.class));
        ScriptedConsole io = new ScriptedConsole("d", "q");
        ManagementTui screen = new ManagementTui(catalog, chains, failingExecutions,
                newsEvents, new ContentStoreService(new InMemoryContentStorePort(), "work"),
                info, new ScheduleProperties(List.of()),
                mock(AiAssistantService.class), mock(SelectorStorePort.class),
                new es.jllopez.neis.application.PreconfiguredSelectorProperties(List.of()),
                new es.jllopez.neis.application.PreconfiguredFilterProperties(List.of()),
                io);

        screen.run();

        String transcript = io.transcript();
        assertTrue(transcript.contains("Failed run     :"), transcript);
        assertTrue(transcript.contains("boom: selector invalid"), transcript);
    }

    @Test
    void logsSectionShowsTheLogLocationAndTailsTheFile() throws Exception {
        java.nio.file.Path logDir = java.nio.file.Files.createTempDirectory("neis-logs");
        java.nio.file.Path log = logDir.resolve("neis.log");
        java.nio.file.Files.writeString(log, "line one\nline two\nline three\n");
        System.setProperty("neis.log.path", log.toString());
        try {
            ScriptedConsole io = new ScriptedConsole(
                    "o",
                    "2",                        // tail the last 2 lines
                    "b",
                    "q");

            tui(io).run();

            String transcript = io.transcript();
            assertTrue(transcript.contains("LOGS (US-16.1)"), transcript);
            assertTrue(transcript.contains("Last 2 of 3 lines"), transcript);
            assertTrue(transcript.contains("line two"), transcript);
            assertTrue(transcript.contains("line three"), transcript);
        } finally {
            System.clearProperty("neis.log.path");
            java.nio.file.Files.deleteIfExists(log);
            java.nio.file.Files.deleteIfExists(logDir);
        }
    }

    @Test
    void configSectionShowsTheKeyAndPreconfiguredEntries() {
        ScriptedConsole io = new ScriptedConsole("n", "b", "q");

        tui(io).run();

        String transcript = io.transcript();
        assertTrue(transcript.contains("CONFIGURATION (US-16.5)"), transcript);
        assertTrue(transcript.contains("General settings (US-15.1)"), transcript);
        assertTrue(transcript.contains("Extraction limit"), transcript);
        assertTrue(transcript.contains("AI provider (US-15.3)"), transcript);
        assertTrue(transcript.contains("Preconfigured selectors (US-12.1"), transcript);
        assertTrue(transcript.contains("<none configured>"), transcript);
    }

    @Test
    void repositoryFilterNarrowsByCompleteness() {
        seedNews(); // complete item
        newsStore.saveAll("ADDR-001", "news", List.of(
                new NewsEventItem("ADDR-001", "news", "Only a title",
                        null, null, null)),
                null, null); // incomplete item
        ScriptedConsole io = new ScriptedConsole(
                "r",
                "f", "", "", "incomplete", "",   // filter: incomplete only
                "b",
                "q");

        tui(io).run();

        String transcript = io.transcript();
        assertTrue(transcript.contains("incomplete only"), transcript);
        assertTrue(transcript.contains("Only a title"), transcript);
        // the complete item ('A title') is filtered out of the LISTING:
        // check the rendered table rows, not the raw transcript (the
        // dashboard elsewhere shows 'Only a title' too)
        assertFalse(transcript.contains("  1. 2026-11-10  news  [ADDR-001]  A title"),
                "the complete item must not be listed when filtering incomplete-only");
        assertTrue(transcript.contains("INCOMPLETE"), transcript);
    }

    @Test
    void executionFilterNarrowsByStatusAndIsClearedByAnEmptyAnswer() {
        seedExecution(); // one SUCCESS record
        ScriptedConsole io = new ScriptedConsole(
                "e",
                "f", "", "", "FAILED", "",      // filter: FAILED only -> nothing matches
                "l",                            // redraw with the filter active
                "f", "", "", "", "",            // empty answer clears it
                "b",
                "q");

        tui(io).run();

        String transcript = io.transcript();
        assertTrue(transcript.contains("match status=FAILED"), transcript);
        assertTrue(transcript.contains("showing every execution again"), transcript);
    }

    @Test
    void reRunResubmitsTheOriginalParametersAndTriggerType() {
        // US-16.3 review finding #97: a re-run that dropped the original
        // run's parameters (resubmitting with defaults) or always marked
        // the re-run MANUAL was not a faithful re-run. The V12-persisted
        // parameter map makes faithfulness possible; this pins it.
        seedVerifiedAddress();
        executions.submit("WEBSCRAPE", "ADDR-001", TriggerType.SCHEDULED,
                java.util.Map.of("selector", "ul.items > li", "max-items", "5"));
        int recordsBefore = executions.list().size();
        ScriptedConsole io = new ScriptedConsole("e", "r", "1", "b", "q");

        tui(io).run();

        assertEquals(recordsBefore + 1, executions.list().size(),
                "the re-run leaves its own execution record");
        TaskExecution reRun = executions.list().get(0); // newest first
        assertEquals(TriggerType.SCHEDULED, reRun.triggerType(),
                "the re-run keeps the original trigger type");
        assertEquals(java.util.Map.of("selector", "ul.items > li", "max-items", "5"),
                reRun.parameters(), "the re-run carries the original parameters");
        assertTrue(io.transcript().contains("same parameters"), io.transcript());
        assertTrue(io.fedAllInputs());
    }

    @Test
    void executionDetailShowsTheRunParameters() {
        seedVerifiedAddress();
        executions.submit("WEBSCRAPE", "ADDR-001", TriggerType.MANUAL,
                java.util.Map.of("selector", "ul.items > li"));
        ScriptedConsole io = new ScriptedConsole("e", "1", "b", "q");

        tui(io).run();

        assertTrue(io.transcript().contains("params   : {selector=ul.items > li}"),
                io.transcript());
        assertTrue(io.fedAllInputs());
    }

    @Test
    void nestedAddressScreenQuitDoesNotClaimAnExit() {
        // US-16.1 review finding #97: quitting the nested address screen
        // printed 'Bye.' — an exit that did not happen (control returns
        // to the management menu). The nested quit is now silent; only
        // the management screen's own quit says goodbye.
        seedVerifiedAddress();
        ScriptedConsole io = new ScriptedConsole("a", "q", "q");

        tui(io).run();

        String transcript = io.transcript();
        assertTrue(transcript.contains("Address management (US-09.1)"), transcript);
        assertEquals(1, transcript.split("Bye\\.", -1).length - 1,
                "exactly one 'Bye.' — the management screen's own quit:\n" + transcript);
        assertTrue(transcript.contains("Sections: [d]ashboard"), transcript);
    }

    @Test
    void chainsSectionRefusesARecordNumberWhenNothingIsListed() {
        // US-16.1 review observation #97: a number typed into an empty
        // list used to answer "Unknown command '5'" instead of a
        // nothing-listed refusal.
        ScriptedConsole io = new ScriptedConsole("c", "5", "b", "q");

        tui(io).run();

        assertTrue(io.transcript().contains("Nothing listed — no chains defined yet"),
                io.transcript());
        assertTrue(io.fedAllInputs());
    }

    @Test
    void dashboardRendersActiveAndFailedBucketsFromTheRecords() {
        // US-16.2 review coverage gap #97: the failed/active buckets were
        // untested (the dashboard test seeded only a SUCCESS run).
        seedVerifiedAddress();
        executionStore.save(new TaskExecution("EXEC-RUN-1", "WEBSCRAPE", "ADDR-001",
                null, TriggerType.SCHEDULED, ExecutionStatus.RUNNING,
                java.time.Instant.now(), null, null));
        executionStore.save(new TaskExecution("EXEC-FAIL-1", "PRINT", "ADDR-001",
                null, TriggerType.MANUAL, ExecutionStatus.FAILED,
                java.time.Instant.now(), java.time.Instant.now(),
                "IllegalStateException: printer on fire"));
        ScriptedConsole io = new ScriptedConsole("d", "q");

        tui(io).run();

        String transcript = io.transcript();
        assertTrue(transcript.contains("1 active, 1 failed"), transcript);
        assertTrue(transcript.contains("Failed run"), transcript);
        assertTrue(transcript.contains("printer on fire"), transcript);
        assertTrue(io.fedAllInputs());
    }

    @Test
    void dashboardWithNoExecutionsRendersZeroBuckets() {
        // US-16.2 review coverage gap #97: the no-execution path.
        ScriptedConsole io = new ScriptedConsole("d", "q");

        tui(io).run();

        String transcript = io.transcript();
        assertTrue(transcript.contains("0 records (0 active, 0 failed)"), transcript);
        assertFalse(transcript.contains("Last run"), transcript);
        assertTrue(io.fedAllInputs());
    }

    @Test
    void configSectionRendersSchedulesStoredSelectorsAndPreconfiguredSets() {
        // US-16.5 review coverage gap #97: schedules rendered, stored
        // selectors displayed and preconfigured config shown were
        // untested.
        seedVerifiedAddress();
        es.jllopez.neis.domain.port.SelectorStorePort selectorStore =
                mock(es.jllopez.neis.domain.port.SelectorStorePort.class);
        when(selectorStore.load("ADDR-001")).thenReturn(java.util.Optional.of(
                new es.jllopez.neis.domain.model.StoredSelectors("ADDR-001", "news",
                        "ul.items > li", "form.filters",
                        List.of(new es.jllopez.neis.domain.model.FilterDefinition(
                                "programme", List.of("EU4Health", "Horizon"))),
                        "ai", "2026-11-16", "test fixture")));
        ScheduleProperties schedules = new ScheduleProperties(List.of(
                new ScheduleProperties.Entry("nightly-scrape", "WEBSCRAPE",
                        "ADDR-001", "0 5 3 * * *")));
        es.jllopez.neis.application.PreconfiguredSelectorProperties selectorSets =
                new es.jllopez.neis.application.PreconfiguredSelectorProperties(
                        List.of(new es.jllopez.neis.application.PreconfiguredSelectorProperties
                                .Entry("ADDR-001", "news", "ul.items > li")));
        es.jllopez.neis.application.PreconfiguredFilterProperties filterSets =
                new es.jllopez.neis.application.PreconfiguredFilterProperties(
                        List.of(new es.jllopez.neis.application.PreconfiguredFilterProperties
                                .Entry("ADDR-001", "programme", "EU4Health")));
        ManagementInfoService info = new ManagementInfoService(catalog, chains,
                executions, newsEvents, schedules, mock(AiAssistantService.class));
        ScriptedConsole io = new ScriptedConsole(
                "g",
                "s", "ADDR-001",            // the stored selectors of ADDR-001
                "b",
                "q");
        ManagementTui screen = new ManagementTui(catalog, chains, executions,
                newsEvents, new ContentStoreService(new InMemoryContentStorePort(), "work"),
                info, schedules, mock(AiAssistantService.class),
                selectorStore, selectorSets, filterSets, io);

        screen.run();

        String transcript = io.transcript();
        assertTrue(transcript.contains("Schedules (1)"), transcript);
        assertTrue(transcript.contains("nightly-scrape"), transcript);
        assertTrue(transcript.contains("Preconfigured selectors"), transcript);
        assertTrue(transcript.contains("Preconfigured filters"), transcript);
        assertTrue(transcript.contains("EU4Health"), transcript);
        assertTrue(transcript.contains("Stored selectors for ADDR-001"), transcript);
        assertTrue(transcript.contains("filter catalogue"), transcript);
        assertTrue(io.fedAllInputs());
    }

    @Test
    void configEditValidatesSavesAndAppliesOverrides() {
        // US-16.5 review finding #97 (blocking): the configuration was
        // view-only while the requirement says "viewed and edited". The
        // edit path validates before save (a typo stores nothing),
        // persists the override and applies it live.
        seedVerifiedAddress();
        InMemorySettingsStorePort settingsStore = new InMemorySettingsStorePort();
        SettingsService settings = new SettingsService(settingsStore);
        es.jllopez.neis.infrastructure.adapter.config.SettingsOverrideApplier applier =
                mock(es.jllopez.neis.infrastructure.adapter.config.SettingsOverrideApplier.class);
        ScriptedConsole io = new ScriptedConsole(
                "g",
                "e",
                "1",            // neis.scrape.connector
                "live",         // valid value -> saved and applied
                "e",
                "2",            // neis.extraction.max-items
                "many",         // invalid value -> refused, nothing stored
                "e",
                "1",
                "",             // empty value -> override cleared
                "b",
                "q");
        ManagementTui screen = new ManagementTui(catalog, chains, executions,
                newsEvents, new ContentStoreService(new InMemoryContentStorePort(), "work"),
                info(), new ScheduleProperties(List.of()),
                mock(AiAssistantService.class), mock(SelectorStorePort.class),
                new es.jllopez.neis.application.PreconfiguredSelectorProperties(List.of()),
                new es.jllopez.neis.application.PreconfiguredFilterProperties(List.of()),
                io, settings, applier);

        screen.run();

        String transcript = io.transcript();
        assertTrue(transcript.contains("Saved and applied: neis.scrape.connector = live"),
                transcript);
        assertTrue(transcript.contains("Refused: 'many' is not a positive integer"),
                transcript);
        assertTrue(transcript.contains("Saved and applied: neis.scrape.connector = (override cleared)"),
                transcript);
        // The invalid attempt stored nothing; the valid one was stored
        // and then cleared.
        assertTrue(settingsStore.all().isEmpty(), settingsStore.all().toString());
        org.mockito.Mockito.verify(applier)
                .apply(SettingsService.SCRAPE_CONNECTOR, "live");
        org.mockito.Mockito.verify(applier, org.mockito.Mockito.never())
                .apply(SettingsService.EXTRACTION_MAX_ITEMS, "many");
        assertTrue(io.fedAllInputs());
    }

    @Test
    void reRunRefusesARecordThatIsStillRunning() throws Exception {
        seedVerifiedAddress();
        // a task that blocks until the test releases it: the record
        // stays RUNNING while the TUI re-run prompt is answered. A real
        // (thread-pool) executor is required — the DIRECT executor runs
        // the work inline and submit() would never return.
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.Executor async = java.util.concurrent.Executors.newSingleThreadExecutor();
        TaskWorkService blocking = mock(TaskWorkService.class);
        when(blocking.execute(anyString(), any(Address.class), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    release.await();
                    return "done";
                });
        ExecutionService slowExecutions = new ExecutionService(executionStore,
                chainStore, catalog, new UrlSyntaxValidator(), blocking, async, 5);
        slowExecutions.submit("WEBSCRAPE", "ADDR-001", TriggerType.MANUAL);
        try {
            TaskExecution submitted = slowExecutions.await(slowExecutions.list().get(0).id());
            // await() returns the record even on timeout; it must still be in flight
            if (submitted != null && (submitted.status() == ExecutionStatus.PENDING
                    || submitted.status() == ExecutionStatus.RUNNING)) {
                ManagementInfoService info = new ManagementInfoService(catalog, chains,
                        slowExecutions, newsEvents,
                        new ScheduleProperties(List.of()), mock(AiAssistantService.class));
                ScriptedConsole io = new ScriptedConsole("e", "r", "1", "b", "q");
                ManagementTui screen = new ManagementTui(catalog, chains, slowExecutions,
                        newsEvents, new ContentStoreService(new InMemoryContentStorePort(), "work"),
                        info, new ScheduleProperties(List.of()),
                        mock(AiAssistantService.class), mock(SelectorStorePort.class),
                        new es.jllopez.neis.application.PreconfiguredSelectorProperties(List.of()),
                        new es.jllopez.neis.application.PreconfiguredFilterProperties(List.of()),
                        io);
                screen.run();
                assertTrue(io.transcript().contains("is still"), io.transcript());
            }
        } finally {
            release.countDown();
            if (async instanceof java.util.concurrent.ExecutorService service) {
                service.shutdownNow();
            }
        }
    }

    @Test
    void contentTreeDrillsDownToAFileAndPreviewsIt() throws Exception {
        // real stored content on disk: a capture with a scrape.html
        java.nio.file.Path work = java.nio.file.Files.createTempDirectory("neis-content");
        java.nio.file.Path file = work.resolve("ADDR-001/cap-abc123/content/WEBSCRAPE/scrape.html");
        java.nio.file.Files.createDirectories(file.getParent());
        java.nio.file.Files.writeString(file,
                "<html><head><style>.x{}</style></head><body>"
                        + "<div class='item'><h2>Headline one</h2><p>Body text.</p></div>"
                        + "<script>var x=1;</script></body></html>");
        InMemoryContentStorePort port = new InMemoryContentStorePort();
        port.saveContent(new es.jllopez.neis.domain.model.ContentRecord(
                "CONT-test", "ADDR-001", "cap-abc123", "WEBSCRAPE", "html",
                file.toString(), java.time.Instant.now()));
        ContentStoreService store = new ContentStoreService(port, work.toString());
        ScriptedConsole io = new ScriptedConsole(
                "r",
                "t",                        // tree: level 1 (addresses)
                "1",                        // drill into ADDR-001
                "1",                        // drill into cap-abc123
                "1",                        // drill into WEBSCRAPE/
                "1",                        // preview scrape.html
                "b",                        // back to the repository menu
                "b",
                "q");
        ManagementTui screen = new ManagementTui(catalog, chains, executions,
                newsEvents, store, info(), new ScheduleProperties(List.of()),
                mock(AiAssistantService.class), mock(SelectorStorePort.class),
                new es.jllopez.neis.application.PreconfiguredSelectorProperties(List.of()),
                new es.jllopez.neis.application.PreconfiguredFilterProperties(List.of()),
                io);

        try {
            screen.run();
        } finally {
            System.clearProperty("neis.log.path");
        }

        String transcript = io.transcript();
        assertTrue(transcript.contains("CONTENT TREE"), transcript);
        assertTrue(transcript.contains("Addresses with stored content"), transcript);
        assertTrue(transcript.contains("cap-abc123"), transcript);
        assertTrue(transcript.contains("Preview of CONT-test"), transcript);
        assertTrue(transcript.contains("Headline one"), transcript);
        assertFalse(transcript.contains("var x=1;"), "script content is dropped");
        assertFalse(transcript.contains(".x{}"), "style content is dropped");
        for (java.nio.file.Path root = work; root != null
                && java.nio.file.Files.isDirectory(root); ) {
            // best-effort cleanup of the temp tree
            break;
        }
        java.util.stream.Stream<java.nio.file.Path> walk =
                java.nio.file.Files.walk(work);
        List<java.nio.file.Path> all = walk.sorted(java.util.Comparator.reverseOrder()).toList();
        walk.close();
        for (java.nio.file.Path p : all) {
            java.nio.file.Files.deleteIfExists(p);
        }
    }
}
