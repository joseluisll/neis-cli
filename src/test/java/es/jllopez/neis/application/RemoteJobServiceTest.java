package es.jllopez.neis.application;

import es.jllopez.neis.application.AddressCatalogService;
import es.jllopez.neis.application.ChainService;
import es.jllopez.neis.domain.model.Address;
import es.jllopez.neis.domain.model.AddressType;
import es.jllopez.neis.domain.model.Chain;
import es.jllopez.neis.domain.model.ChainTask;
import es.jllopez.neis.domain.model.ExecutionStatus;
import es.jllopez.neis.domain.model.JobStatus;
import es.jllopez.neis.domain.model.RemoteJob;
import es.jllopez.neis.domain.model.TaskExecution;
import es.jllopez.neis.domain.model.TriggerType;
import es.jllopez.neis.testsupport.InMemoryJobStorePort;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.Executors;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The asynchronous remote-job model (US-18.2, issue #117): a job
 * returns QUEUED immediately, wraps the execution layer (the run goes
 * through ExecutionService with the REMOTE trigger and the job links
 * the produced execution record), drives its own lifecycle to a
 * terminal state, and records the failure reason — including a
 * refusal at submission (unknown task, ineligible address).
 */
class RemoteJobServiceTest {

    private static final Executor DIRECT = Runnable::run;

    private final InMemoryJobStorePort store = new InMemoryJobStorePort();

    private RemoteJobService service(ExecutionService executions) {
        return service(executions, mock(ChainService.class),
                mock(AddressCatalogService.class));
    }

    private RemoteJobService service(ExecutionService executions,
                                     ChainService chains,
                                     AddressCatalogService catalog) {
        return new RemoteJobService(store, executions, chains, catalog,
                DIRECT, "core-chain-ssafp");
    }

    private TaskExecution execution(ExecutionStatus status, String outcome) {
        boolean terminal = status == ExecutionStatus.SUCCESS
                || status == ExecutionStatus.FAILED;
        return new TaskExecution("EXEC-test", "WEBSCRAPE", "ADDR-001", null,
                TriggerType.REMOTE, status, Instant.now(),
                terminal ? Instant.now() : null, outcome, Map.of());
    }

    @Test
    void submitRunTaskReturnsQueuedJobImmediatelyAndRunsThroughExecutionService() {
        ExecutionService executions = mock(ExecutionService.class);
        when(executions.submit(eq("WEBSCRAPE"), eq("ADDR-001"),
                eq(TriggerType.REMOTE), any()))
                .thenReturn(execution(ExecutionStatus.SUCCESS, "WEBSCRAPE: 20 items"));
        // the job polls the execution record through show(); an unstubbed
        // mock reads as absent — stub it or the job waits forever
        when(executions.show(anyString()))
                .thenReturn(execution(ExecutionStatus.SUCCESS, "WEBSCRAPE: 20 items"));
        RemoteJobService service = service(executions);

        RemoteJob submitted = service.submitRunTask("WEBSCRAPE", "ADDR-001",
                Map.of("max-items", "5"));

        assertEquals(JobStatus.QUEUED, submitted.status());
        assertNotNull(submitted.id());
        assertTrue(submitted.id().startsWith("JOB-"));
        assertEquals("run-task", submitted.operation());
        assertEquals("ADDR-001", submitted.addressId());
        // the caller's parameters ride the job record (plus the task code)
        assertEquals("WEBSCRAPE", submitted.parameters().get("task-code"));
        assertEquals("5", submitted.parameters().get("max-items"));
        // with the DIRECT runner the job already finished; read it back
        RemoteJob finished = service.job(submitted.id()).orElseThrow();
        assertEquals(JobStatus.COMPLETED, finished.status());
        assertEquals("WEBSCRAPE: 20 items", finished.result());
        assertEquals("EXEC-test", finished.executionId());
        assertNull(finished.error());
        // the wrapped run went through the execution layer, REMOTE-triggered
        verify(executions).submit(eq("WEBSCRAPE"), eq("ADDR-001"),
                eq(TriggerType.REMOTE), any());
    }

    @Test
    void submitRunChainUsesTheChainPathWithTheRemoteTrigger() {
        ExecutionService executions = mock(ExecutionService.class);
        when(executions.submitChain("CHAIN-1", "ADDR-001", TriggerType.REMOTE))
                .thenReturn(execution(ExecutionStatus.SUCCESS, "chain ok"));
        when(executions.show(anyString()))
                .thenReturn(execution(ExecutionStatus.SUCCESS, "chain ok"));
        RemoteJobService service = service(executions);

        RemoteJob submitted = service.submitRunChain("CHAIN-1", "ADDR-001");

        assertEquals("run-chain", submitted.operation());
        assertEquals("CHAIN-1", submitted.parameters().get("chain-id"));
        RemoteJob finished = service.job(submitted.id()).orElseThrow();
        assertEquals(JobStatus.COMPLETED, finished.status());
        verify(executions).submitChain("CHAIN-1", "ADDR-001", TriggerType.REMOTE);
    }

    @Test
    void aFailedExecutionFailsTheJobWithTheExecutionOutcomeAsTheReason() {
        ExecutionService executions = mock(ExecutionService.class);
        when(executions.submit(anyString(), anyString(), eq(TriggerType.REMOTE), any()))
                .thenReturn(execution(ExecutionStatus.FAILED,
                        "FetchException: connection refused"));
        when(executions.show(anyString()))
                .thenReturn(execution(ExecutionStatus.FAILED,
                        "FetchException: connection refused"));
        RemoteJobService service = service(executions);

        RemoteJob submitted = service.submitRunTask("WEBSCRAPE", "ADDR-001", null);

        RemoteJob finished = service.job(submitted.id()).orElseThrow();
        assertEquals(JobStatus.FAILED, finished.status());
        assertNull(finished.result());
        assertEquals("FetchException: connection refused", finished.error());
        assertEquals("EXEC-test", finished.executionId());
    }

    @Test
    void aPreCheckRefusalThrowsSynchronouslyAndPersistsNothing() {
        // US-18.1 review finding, issue #127: the SUBMIT-time pre-check
        // refuses before the job record exists — the caller gets the
        // 4xx immediately instead of polling a doomed QUEUED job
        ExecutionService executions = mock(ExecutionService.class);
        doThrow(new IllegalArgumentException(
                        "Unknown task type: BOGUS (known: [WEBSCRAPE, ...])"))
                .when(executions).validateRemoteSubmission("BOGUS", "ADDR-001");
        RemoteJobService service = service(executions);

        assertThrows(IllegalArgumentException.class,
                () -> service.submitRunTask("BOGUS", "ADDR-001", null));
        assertEquals(0, store.findAll().size());
        verify(executions, never()).submit(anyString(), anyString(), any(), any());

        // chain variant: same shape
        doThrow(new IllegalArgumentException("Unknown chain: CHAIN-nope"))
                .when(executions).validateRemoteChainSubmission("CHAIN-nope", "ADDR-001");
        assertThrows(IllegalArgumentException.class,
                () -> service.submitRunChain("CHAIN-nope", "ADDR-001"));
        assertEquals(0, store.findAll().size());
    }

    @Test
    void aSubmissionRefusalFailsTheJobWithTheRefusalReasonAndNoExecution() {
        ExecutionService executions = mock(ExecutionService.class);
        when(executions.submit(anyString(), anyString(), eq(TriggerType.REMOTE), any()))
                .thenThrow(new IllegalStateException(
                        "Address ADDR-001 is not eligible: verification status is UNVERIFIED"));
        RemoteJobService service = service(executions);

        RemoteJob submitted = service.submitRunTask("WEBSCRAPE", "ADDR-001", null);

        RemoteJob finished = service.job(submitted.id()).orElseThrow();
        assertEquals(JobStatus.FAILED, finished.status());
        assertNull(finished.executionId());
        assertNotNull(finished.error());
        assertTrue(finished.error().contains("not eligible"));
    }

    @Test
    // ---- US-18.4 (issue #119): the fetch-news job ----------------------

    private Chain fetchChain() {
        return new Chain("CHAIN-1", "core-chain-ssafp", "the fetch chain",
                Instant.now(), List.of(new ChainTask(1, "WEBSCRAPE", Map.of())));
    }

    private Address address(String id) {
        return new Address(id, id, null, "https://example.eu/" + id,
                AddressType.URL_NEWS, java.util.Set.of(),
                es.jllopez.neis.domain.model.VerificationStatus.VERIFIED,
                null, null);
    }

    @Test
    void fetchNewsOverOneAddressRunsTheChainAndCompletesWithTheOutcome() {
        ExecutionService executions = mock(ExecutionService.class);
        ChainService chains = mock(ChainService.class);
        when(chains.list()).thenReturn(List.of(fetchChain()));
        when(executions.submitChain("CHAIN-1", "ADDR-001", TriggerType.REMOTE))
                .thenReturn(execution(ExecutionStatus.SUCCESS,
                        "step 5/5 PRINT: 20 items"));
        when(executions.show(anyString()))
                .thenReturn(execution(ExecutionStatus.SUCCESS,
                        "step 5/5 PRINT: 20 items"));
        RemoteJobService service = service(executions, chains,
                mock(AddressCatalogService.class));

        RemoteJob submitted = service.submitFetchNews("ADDR-001");

        assertEquals("fetch-news", submitted.operation());
        RemoteJob finished = service.job(submitted.id()).orElseThrow();
        assertEquals(JobStatus.COMPLETED, finished.status());
        assertTrue(finished.result().contains("ADDR-001: SUCCESS"));
        assertEquals("EXEC-test", finished.executionId());
        // pre-check (existing chain) + re-validation after resolution
        verify(executions, times(2)).validateRemoteChainSubmission(
                "CHAIN-1", "ADDR-001");
    }

    @Test
    void fetchNewsOverAllAddressesCollectsPerAddressOutcomesAndFailsOnAnyFailure() {
        ExecutionService executions = mock(ExecutionService.class);
        ChainService chains = mock(ChainService.class);
        AddressCatalogService catalog = mock(AddressCatalogService.class);
        when(chains.list()).thenReturn(List.of(fetchChain()));
        when(catalog.list()).thenReturn(List.of(
                address("ADDR-001"), address("ADDR-002")));
        when(executions.submitChain("CHAIN-1", "ADDR-001", TriggerType.REMOTE))
                .thenReturn(execution(ExecutionStatus.SUCCESS, "ok"));
        when(executions.submitChain("CHAIN-1", "ADDR-002", TriggerType.REMOTE))
                .thenReturn(execution(ExecutionStatus.FAILED, "boom at scrape"));
        when(executions.show(anyString()))
                .thenReturn(execution(ExecutionStatus.SUCCESS, "ok"))
                .thenReturn(execution(ExecutionStatus.FAILED, "boom at scrape"));
        RemoteJobService service = service(executions, chains, catalog);

        RemoteJob submitted = service.submitFetchNews(null);

        RemoteJob finished = service.job(submitted.id()).orElseThrow();
        // an honest summary: a batch with a failing address is FAILED,
        // and the per-address outcomes are the reason
        assertEquals(JobStatus.FAILED, finished.status());
        assertTrue(finished.error().contains("ADDR-001: SUCCESS"));
        assertTrue(finished.error().contains("ADDR-002: FAILED"));
        assertTrue(finished.error().contains("boom at scrape"));
    }

    @Test
    void fetchNewsCreatesTheDefaultChainWhenTheConfiguredOneIsAbsent() {
        ExecutionService executions = mock(ExecutionService.class);
        ChainService chains = mock(ChainService.class);
        when(chains.list()).thenReturn(List.of());
        Chain created = fetchChain();
        when(chains.create(eq("core-chain-ssafp"), anyString(), any()))
                .thenReturn(created);
        when(executions.submitChain("CHAIN-1", "ADDR-001", TriggerType.REMOTE))
                .thenReturn(execution(ExecutionStatus.SUCCESS, "ok"));
        when(executions.show(anyString()))
                .thenReturn(execution(ExecutionStatus.SUCCESS, "ok"));
        RemoteJobService service = service(executions, chains,
                mock(AddressCatalogService.class));

        service.submitFetchNews("ADDR-001");

        // the default chain carries the SSAFP stages in pipeline order
        verify(chains).create(eq("core-chain-ssafp"), anyString(),
                argThat(steps -> steps.size() == 5
                        && "WEBSCRAPE".equals(steps.get(0).taskCode())
                        && "PRINT".equals(steps.get(4).taskCode())));
    }

    @Test
    void fetchNewsOverAllAddressesWithAnEmptyCatalogRefusesSynchronously() {
        AddressCatalogService catalog = mock(AddressCatalogService.class);
        when(catalog.list()).thenReturn(List.of());
        RemoteJobService service = service(mock(ExecutionService.class),
                mock(ChainService.class), catalog);

        assertThrows(IllegalArgumentException.class,
                () -> service.submitFetchNews(null));
        assertEquals(0, store.findAll().size());
    }

    // ---- US-18.7 (issue #122): job cancellation ------------------------

    /** A real single-thread runner for the RUNNING/QUEUED cancel tests
     * (daemon factory — a blocked worker must never hang the JVM). */
    private RemoteJobService asyncService(ExecutionService executions,
                                          ChainService chains,
                                          AddressCatalogService catalog) {
        return new RemoteJobService(store, executions, chains, catalog,
                Executors.newSingleThreadExecutor(r -> {
                    Thread t = new Thread(r, "test-job-runner");
                    t.setDaemon(true);
                    return t;
                }), "core-chain-ssafp");
    }

    private void awaitStatus(RemoteJobService service, String jobId,
                             JobStatus wanted) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            RemoteJob job = service.job(jobId).orElseThrow();
            if (job.status() == wanted) {
                return;
            }
            Thread.sleep(25);
        }
        org.junit.jupiter.api.Assertions.fail("job never reached " + wanted);
    }

    @Test
    void cancelOfAFinishedJobIsANoOpWithAClearNote() throws Exception {
        ExecutionService executions = mock(ExecutionService.class);
        when(executions.submit(anyString(), anyString(), any(), any()))
                .thenReturn(execution(ExecutionStatus.SUCCESS, "ok"));
        when(executions.show(anyString()))
                .thenReturn(execution(ExecutionStatus.SUCCESS, "ok"));
        RemoteJobService service = service(executions);
        RemoteJob finished = service.submitRunTask("WEBSCRAPE", "ADDR-001", null);
        awaitStatus(service, finished.id(), JobStatus.COMPLETED);

        var outcome = service.cancel(finished.id());

        assertTrue(!outcome.cancelled());
        assertTrue(outcome.note().contains("already finished"));
        assertEquals(JobStatus.COMPLETED, outcome.job().status());
        assertEquals(JobStatus.COMPLETED,
                service.job(finished.id()).orElseThrow().status());
    }

    @Test
    void cancelOfAnUnknownJobRefuses() {
        RemoteJobService service = service(mock(ExecutionService.class));
        assertThrows(NoSuchElementException.class,
                () -> service.cancel("JOB-nope"));
    }

    @Test
    void cancelOfARunningJobInterruptsTheWaitAndFinalizesCancelled()
            throws Exception {
        ExecutionService executions = mock(ExecutionService.class);
        TaskExecution pending = execution(ExecutionStatus.PENDING, null);
        TaskExecution done = execution(ExecutionStatus.SUCCESS,
                "step 5/5 PRINT: 20 items");
        when(executions.submit(anyString(), anyString(),
                eq(TriggerType.REMOTE), any())).thenReturn(done);
        // PENDING while the runner waits; SUCCESS after the cancel wakes
        // the poll — so the RUNNER (not cancel()'s own write) hits the
        // finalize and its CAS guard preserves the CANCELLED record with
        // the outcome kept (the vacuous-test review finding)
        java.util.concurrent.atomic.AtomicInteger polls =
                new java.util.concurrent.atomic.AtomicInteger();
        when(executions.show(anyString())).thenAnswer(inv -> {
            // poll 1 (and its post-interrupt re-read) see PENDING; the
            // re-read AFTER the cancel's interrupt sees the finished
            // execution — the deterministic path to the outcome-kept
            // finalize guard
            if (polls.incrementAndGet() <= 1) {
                return pending;
            }
            return done;
        });
        RemoteJobService service = asyncService(executions,
                mock(ChainService.class), mock(AddressCatalogService.class));

        RemoteJob submitted = service.submitRunTask("WEBSCRAPE", "ADDR-001", null);
        awaitStatus(service, submitted.id(), JobStatus.RUNNING);

        var outcome = service.cancel(submitted.id());

        assertTrue(outcome.cancelled());
        awaitStatus(service, submitted.id(), JobStatus.CANCELLED);
        // the runner wakes from the interrupt and finalizes (preserving
        // the outcome on the CANCELLED record) a moment later — wait for
        // ITS write instead of racing it
        long deadline = System.currentTimeMillis() + 5000;
        RemoteJob finished = service.job(submitted.id()).orElseThrow();
        while (finished.result() == null
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(25);
            finished = service.job(submitted.id()).orElseThrow();
        }
        // CANCELLED is DISTINGUISHABLE from FAILED, carries the reason,
        // AND the runner's finalize (which observed the SUCCESS outcome)
        // did NOT overwrite it — the outcome is kept on the record
        assertEquals(JobStatus.CANCELLED, finished.status());
        assertTrue(finished.error().contains("Cancelled by the caller"));
        assertEquals("step 5/5 PRINT: 20 items", finished.result());
    }

    @Test
    void cancelOfAQueuedJobPreventsItsRun() throws Exception {
        ExecutionService executions = mock(ExecutionService.class);
        TaskExecution pending = execution(ExecutionStatus.PENDING, null);
        // job A occupies the single worker inside awaitTerminal;
        // job B stays QUEUED until A finishes
        when(executions.submit(eq("WEBSCRAPE"), eq("ADDR-001"),
                eq(TriggerType.REMOTE), any())).thenReturn(pending);
        when(executions.show(anyString())).thenReturn(pending);
        when(executions.submit(eq("SIMPLIFY"), eq("ADDR-001"),
                eq(TriggerType.REMOTE), any())).thenReturn(pending);
        RemoteJobService service = asyncService(executions,
                mock(ChainService.class), mock(AddressCatalogService.class));
        RemoteJob first = service.submitRunTask("WEBSCRAPE", "ADDR-001", null);
        awaitStatus(service, first.id(), JobStatus.RUNNING);
        RemoteJob second = service.submitRunTask("SIMPLIFY", "ADDR-001", null);
        awaitStatus(service, second.id(), JobStatus.QUEUED);

        var outcome = service.cancel(second.id());

        assertTrue(outcome.cancelled());
        assertEquals(JobStatus.CANCELLED,
                service.job(second.id()).orElseThrow().status());
        // give the worker a moment to reach B's runner; it picks up
        // CANCELLED and aborts WITHOUT ever reaching the execution layer
        Thread.sleep(500);
        assertEquals(JobStatus.CANCELLED,
                service.job(second.id()).orElseThrow().status());
        verify(executions, never()).submit(eq("SIMPLIFY"), eq("ADDR-001"),
                any(), any());
    }

    @Test
    void jobsListIsNewestFirstAndFiltersByStatus() {
        ExecutionService executions = mock(ExecutionService.class);
        when(executions.submit(anyString(), anyString(), eq(TriggerType.REMOTE), any()))
                .thenReturn(execution(ExecutionStatus.SUCCESS, "ok"));
        when(executions.submitChain("CHAIN-1", "ADDR-001", TriggerType.REMOTE))
                .thenReturn(execution(ExecutionStatus.SUCCESS, "chain ok"));
        when(executions.show(anyString()))
                .thenReturn(execution(ExecutionStatus.SUCCESS, "ok"));
        RemoteJobService service = service(executions);

        RemoteJob first = service.submitRunTask("WEBSCRAPE", "ADDR-001", null);
        // distinct submission timestamps: the store orders newest first,
        // and two same-millisecond submissions have an unspecified tie
        try {
            Thread.sleep(10);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        RemoteJob second = service.submitRunChain("CHAIN-1", "ADDR-001");

        List<RemoteJob> all = service.jobs(null);
        assertEquals(2, all.size());
        assertEquals(second.id(), all.get(0).id());
        assertEquals(first.id(), all.get(1).id());
        assertEquals(2, service.jobs(JobStatus.COMPLETED).size());
        assertEquals(0, service.jobs(JobStatus.FAILED).size());
    }
}
