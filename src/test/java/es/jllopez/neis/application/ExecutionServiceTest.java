package es.jllopez.neis.application;

import es.jllopez.neis.domain.model.Address;
import es.jllopez.neis.domain.model.AddressType;
import es.jllopez.neis.domain.model.ExecutionStatus;
import es.jllopez.neis.domain.model.TaskExecution;
import es.jllopez.neis.domain.model.TriggerType;
import es.jllopez.neis.domain.model.VerificationStatus;
import es.jllopez.neis.domain.port.ExecutionStorePort;
import es.jllopez.neis.domain.service.UrlSyntaxValidator;
import es.jllopez.neis.testsupport.InMemoryAddressCatalogPort;
import es.jllopez.neis.testsupport.InMemoryExecutionStorePort;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The asynchronous task executor (US-05.1, issue #24): runs work off
 * the caller thread, drives the execution record's lifecycle
 * (PENDING -> RUNNING -> SUCCESS/FAILED), refuses unknown task types
 * and ineligible addresses (US-04.3), and awaits completion.
 */
class ExecutionServiceTest {

    private static final Executor DIRECT = Runnable::run;

    private final Address verified = new Address("ADDR-001", "Source",
            "https://example.eu/news", AddressType.URL_NEWS)
            .withVerification(VerificationStatus.VERIFIED, "2026-11-11T00:00:00Z");

    private ExecutionService service(TaskWorkService work, Executor executor,
                                     InMemoryExecutionStorePort store) {
        var catalog = new AddressCatalogService(
                new InMemoryAddressCatalogPort(List.of(verified)), new UrlSyntaxValidator());
        return new ExecutionService(store, catalog, new UrlSyntaxValidator(),
                work, executor, 5);
    }

    @SuppressWarnings("unchecked")
    private TaskWorkService workReturning(String outcome) {
        TaskWorkService work = mock(TaskWorkService.class);
        when(work.execute(anyString(), any(Address.class))).thenReturn(outcome);
        return work;
    }

    @Test
    void submitRunsAsynchronouslyAndDrivesTheRecordLifecycle() {
        InMemoryExecutionStorePort store =
                new InMemoryExecutionStorePort(List.of("CHAIN"));
        ExecutionService service = service(workReturning("CHAIN: 20 items"), DIRECT, store);

        TaskExecution submitted = service.submit("CHAIN", "ADDR-001", TriggerType.MANUAL);

        assertEquals(ExecutionStatus.PENDING, submitted.status());
        assertNotNull(submitted.startedAt());
        TaskExecution finished = service.await(submitted.id());
        assertNotNull(finished);
        assertEquals(ExecutionStatus.SUCCESS, finished.status());
        assertNotNull(finished.finishedAt());
        assertEquals("CHAIN: 20 items", finished.outcome());
        // The store holds ONE record per execution (a keyed row, like
        // the DB): its final state proves the whole lifecycle ran.
        assertEquals(1, store.findAll().size());
        assertEquals(ExecutionStatus.SUCCESS, store.findAll().get(0).status());
    }

    @Test
    void aFailingWorkMarksTheRecordFailedWithoutThrowing() {
        InMemoryExecutionStorePort store =
                new InMemoryExecutionStorePort(List.of("WEBSCRAPE"));
        TaskWorkService failing = mock(TaskWorkService.class);
        when(failing.execute(anyString(), any(Address.class)))
                .thenThrow(new IllegalStateException("scrape boom"));
        ExecutionService service = service(failing, DIRECT, store);

        TaskExecution submitted = service.submit("WEBSCRAPE", "ADDR-001",
                TriggerType.SCHEDULED);

        assertEquals(TriggerType.SCHEDULED, submitted.triggerType());
        TaskExecution finished = service.await(submitted.id());
        assertEquals(ExecutionStatus.FAILED, finished.status());
        assertTrue(finished.outcome().contains("scrape boom"));
    }

    @Test
    void unknownTaskTypeIsRefused() {
        ExecutionService service = service(workReturning("x"), DIRECT,
                new InMemoryExecutionStorePort(List.of("CHAIN")));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.submit("NOT_A_TASK", "ADDR-001", TriggerType.MANUAL));
        assertTrue(e.getMessage().contains("Unknown task type"));
    }

    @Test
    void anUnverifiedAddressIsNotEligible() {
        Address unverified = new Address("ADDR-002", "Unverified",
                "https://example.eu/events", AddressType.URL_EVENTS);
        var catalog = new AddressCatalogService(
                new InMemoryAddressCatalogPort(List.of(verified, unverified)),
                new UrlSyntaxValidator());
        ExecutionService service = new ExecutionService(
                new InMemoryExecutionStorePort(List.of("CHAIN")), catalog,
                new UrlSyntaxValidator(), workReturning("x"), DIRECT, 5);

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.submit("CHAIN", "ADDR-002", TriggerType.MANUAL));
        assertTrue(e.getMessage().contains("not eligible"));
        assertTrue(e.getMessage().contains("UNVERIFIED"));
    }

    @Test
    void anUnknownAddressIsRefused() {
        ExecutionService service = service(workReturning("x"), DIRECT,
                new InMemoryExecutionStorePort(List.of("CHAIN")));
        assertThrows(IllegalArgumentException.class,
                () -> service.submit("CHAIN", "ADDR-999", TriggerType.MANUAL));
    }

    @Test
    void aStoreFailureAtTheRunningTransitionDoesNotStrandTheRecord() {
        // The reviewer's reproduced defect: a store throwing at the
        // RUNNING transition used to escape run(), leaving the record
        // PENDING forever and losing the outcome.
        InMemoryExecutionStorePort backing =
                new InMemoryExecutionStorePort(List.of("CHAIN"));
        AtomicInteger saves = new AtomicInteger();
        ExecutionStorePort flaky = new ExecutionStorePort() {
            @Override
            public TaskExecution save(TaskExecution execution) {
                // Fail exactly the RUNNING transition (the second save).
                if (saves.incrementAndGet() == 2
                        && execution.status() == ExecutionStatus.RUNNING) {
                    throw new IllegalStateException("db locked");
                }
                return backing.save(execution);
            }

            @Override
            public java.util.Optional<TaskExecution> findById(String id) {
                return backing.findById(id);
            }

            @Override
            public List<TaskExecution> findAll() {
                return backing.findAll();
            }

            @Override
            public List<String> taskTypes() {
                return backing.taskTypes();
            }
        };
        var catalog = new AddressCatalogService(
                new InMemoryAddressCatalogPort(List.of(verified)), new UrlSyntaxValidator());
        ExecutionService service = new ExecutionService(flaky, catalog,
                new UrlSyntaxValidator(), workReturning("CHAIN: 7 items"), DIRECT, 5);

        TaskExecution submitted = service.submit("CHAIN", "ADDR-001", TriggerType.MANUAL);
        TaskExecution finished = service.await(submitted.id());

        assertNotNull(finished);
        assertEquals(ExecutionStatus.SUCCESS, finished.status());
        assertEquals("CHAIN: 7 items", finished.outcome());
    }

    @Test
    void awaitReturnsNullWhenTheRunExceedsTheTimeout() {
        // The busy executor parks the worker forever: the run never
        // finishes and await() must give up after its timeout.
        java.util.concurrent.CountDownLatch never = new java.util.concurrent.CountDownLatch(1);
        Executor parking = task -> {
            Thread worker = new Thread(() -> {
                try {
                    never.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                task.run();
            }, "parked-worker");
            worker.setDaemon(true);
            worker.start();
        };
        InMemoryExecutionStorePort store =
                new InMemoryExecutionStorePort(List.of("CHAIN"));
        var catalog = new AddressCatalogService(
                new InMemoryAddressCatalogPort(List.of(verified)), new UrlSyntaxValidator());
        ExecutionService service = new ExecutionService(store, catalog,
                new UrlSyntaxValidator(), workReturning("never"), parking, 1);

        TaskExecution submitted = service.submit("CHAIN", "ADDR-001", TriggerType.MANUAL);
        long start = System.nanoTime();
        TaskExecution result = service.await(submitted.id());
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertNull(result, "await must give up with null after the timeout");
        assertTrue(elapsedMs >= 900 && elapsedMs < 4000,
                "the timeout must bound the wait, was " + elapsedMs + " ms");
    }

    @Test
    void runsOverTheSameAddressDoNotOverlap() {
        // The executor pool is 4 threads; two submissions over the SAME
        // address must serialize (shared work tree + selector store).
        List<long[]> intervals =
                java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        TaskWorkService slowWork = mock(TaskWorkService.class);
        when(slowWork.execute(anyString(), any(Address.class))).thenAnswer(inv -> {
            long start = System.nanoTime();
            intervals.add(new long[]{start, 0}); // enter
            Thread.sleep(250);
            intervals.get(intervals.size() - 1)[1] = System.nanoTime(); // exit
            return "slow";
        });
        Executor pool = Executors.newFixedThreadPool(4);
        InMemoryExecutionStorePort store =
                new InMemoryExecutionStorePort(List.of("CHAIN"));
        var catalog = new AddressCatalogService(
                new InMemoryAddressCatalogPort(List.of(verified)), new UrlSyntaxValidator());
        ExecutionService service = new ExecutionService(store, catalog,
                new UrlSyntaxValidator(), slowWork, pool, 10);

        TaskExecution first = service.submit("CHAIN", "ADDR-001", TriggerType.MANUAL);
        TaskExecution second = service.submit("CHAIN", "ADDR-001", TriggerType.MANUAL);
        assertNotNull(service.await(first.id()));
        assertNotNull(service.await(second.id()));

        assertEquals(2, intervals.size());
        long[] a = intervals.get(0);
        long[] b = intervals.get(1);
        boolean disjoint = a[1] <= b[0] || b[1] <= a[0];
        assertTrue(disjoint, "the two same-address runs overlapped: "
                + a[0] + "-" + a[1] + " vs " + b[0] + "-" + b[1]);
    }

    @Test
    void asyncExecutorReallyRunsOffTheCallingThread() {
        // A single-thread executor that parks the WORK (not the caller):
        // submit must return before the work runs (the caller is not
        // blocked, US-05.1).
        java.util.concurrent.CountDownLatch block = new java.util.concurrent.CountDownLatch(1);
        Executor busy = task -> {
            Thread worker = new Thread(() -> {
                try {
                    block.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                task.run();
            }, "busy-executor-worker");
            worker.setDaemon(true);
            worker.start();
        };
        InMemoryExecutionStorePort store =
                new InMemoryExecutionStorePort(List.of("CHAIN"));
        ExecutionService service = service(workReturning("done"), busy, store);

        TaskExecution submitted = service.submit("CHAIN", "ADDR-001", TriggerType.MANUAL);
        assertEquals(ExecutionStatus.PENDING, store.findById(submitted.id())
                .orElseThrow().status(), "the work must not have started yet");

        block.countDown(); // release the worker
        TaskExecution finished = service.await(submitted.id());
        assertEquals(ExecutionStatus.SUCCESS, finished.status());
    }
}
