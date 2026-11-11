package es.jllopez.neis.application;

import es.jllopez.neis.domain.model.TaskExecution;
import es.jllopez.neis.domain.model.TriggerType;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.CronTrigger;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Configurable schedules (US-05.2, issue #25): entries from the
 * configuration register as cron triggers, fire the asynchronous
 * executor with the SCHEDULED trigger type, and a refused submission
 * does not cancel the schedule.
 */
class ScheduleRegistrarTest {

    @SuppressWarnings("unchecked")
    private TaskExecution anyRecord() {
        return mock(TaskExecution.class);
    }

    @Test
    void aConfiguredEntryFiresOnItsCronAndSubmitsAsScheduled() throws Exception {
        ExecutionService executions = mock(ExecutionService.class);
        CountDownLatch fired = new CountDownLatch(1);
        when(executions.submit(anyString(), anyString(), any(TriggerType.class)))
                .thenAnswer(inv -> {
                    fired.countDown();
                    return anyRecord();
                });

        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.initialize();
        try {
            // Every second: the next tick is at most ~1s away.
            new ScheduleRegistrar(scheduler, executions, new ScheduleProperties(
                    List.of(new ScheduleProperties.Entry("test", "WEBSCRAPE",
                            "ADDR-001", "* * * * * *"))));

            assertTrue(fired.await(4, TimeUnit.SECONDS),
                    "the cron trigger must fire the executor within ~4s");
            org.mockito.Mockito.verify(executions, org.mockito.Mockito.atLeastOnce())
                    .submit("WEBSCRAPE", "ADDR-001", TriggerType.SCHEDULED);
        } finally {
            scheduler.shutdown();
        }
    }

    @Test
    void aRefusedSubmissionDoesNotCancelTheSchedule() throws Exception {
        ExecutionService executions = mock(ExecutionService.class);
        CountDownLatch secondAttempt = new CountDownLatch(2);
        when(executions.submit(anyString(), anyString(), any(TriggerType.class)))
                .thenThrow(new IllegalStateException("not eligible"))
                .thenAnswer(inv -> {
                    secondAttempt.countDown();
                    return anyRecord();
                });

        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.initialize();
        try {
            new ScheduleRegistrar(scheduler, executions, new ScheduleProperties(
                    List.of(new ScheduleProperties.Entry("test", "CHAIN",
                            "ADDR-002", "* * * * * *"))));

            // First tick refused, second tick still submitted: the
            // schedule stays active after a refusal.
            assertTrue(secondAttempt.await(6, TimeUnit.SECONDS),
                    "the schedule must retry after a refused submission");
        } finally {
            scheduler.shutdown();
        }
    }

    @Test
    void anInvalidCronFailsFastAtRegistration() {
        ExecutionService executions = mock(ExecutionService.class);
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.initialize();
        try {
            org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                    () -> new ScheduleRegistrar(scheduler, executions,
                            new ScheduleProperties(List.of(
                                    new ScheduleProperties.Entry("bad", "CHAIN",
                                            "ADDR-001", "not a cron")))));
        } finally {
            scheduler.shutdown();
        }
    }

    @Test
    void destroyCancelsEveryRegisteredTriggerHandle() {
        ExecutionService executions = mock(ExecutionService.class);
        TaskScheduler scheduler = mock(TaskScheduler.class);
        ScheduledFuture first = mock(ScheduledFuture.class);
        ScheduledFuture second = mock(ScheduledFuture.class);
        when(scheduler.schedule(any(Runnable.class), any(CronTrigger.class)))
                .thenReturn(first, second);

        ScheduleRegistrar registrar = new ScheduleRegistrar(scheduler, executions,
                new ScheduleProperties(List.of(
                        new ScheduleProperties.Entry("one", "WEBSCRAPE", "ADDR-001", "* * * * * *"),
                        new ScheduleProperties.Entry("two", "SIMPLIFY", "ADDR-001", "* * * * * *"))));

        registrar.destroy();

        // false: a tick already running may finish; only the next fire is prevented.
        org.mockito.Mockito.verify(first).cancel(false);
        org.mockito.Mockito.verify(second).cancel(false);
    }

    @Test
    void aFailedRegistrationCancelsTheHandlesRegisteredBeforeTheFailure() {
        ExecutionService executions = mock(ExecutionService.class);
        TaskScheduler scheduler = mock(TaskScheduler.class);
        ScheduledFuture first = mock(ScheduledFuture.class);
        when(scheduler.schedule(any(Runnable.class), any(CronTrigger.class)))
                .thenReturn(first)
                // Second entry has an invalid cron: CronTrigger is built
                // before schedule() is consulted again.
                .thenThrow(new IllegalArgumentException("bad cron"));

        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new ScheduleRegistrar(scheduler, executions,
                        new ScheduleProperties(List.of(
                                new ScheduleProperties.Entry("ok", "WEBSCRAPE", "ADDR-001", "* * * * * *"),
                                new ScheduleProperties.Entry("bad", "CHAIN", "ADDR-001", "not a cron")))));

        org.mockito.Mockito.verify(first).cancel(false);
    }

    @Test
    void afterDestroyNoFurtherTicksFire() throws Exception {
        ExecutionService executions = mock(ExecutionService.class);
        java.util.concurrent.atomic.AtomicInteger submissions = new java.util.concurrent.atomic.AtomicInteger();
        when(executions.submit(anyString(), anyString(), any(TriggerType.class)))
                .thenAnswer(inv -> {
                    submissions.incrementAndGet();
                    return anyRecord();
                });

        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.initialize();
        try {
            ScheduleRegistrar registrar = new ScheduleRegistrar(scheduler, executions,
                    new ScheduleProperties(List.of(new ScheduleProperties.Entry(
                            "test", "WEBSCRAPE", "ADDR-001", "* * * * * *"))));

            // Wait for the schedule to fire at least once, then cancel it.
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
            while (submissions.get() == 0 && System.nanoTime() < deadline) {
                Thread.sleep(50);
            }
            assertTrue(submissions.get() >= 1, "the schedule must fire before destroy()");

            registrar.destroy();
            int afterCancel = submissions.get();
            TimeUnit.SECONDS.sleep(3); // covers ~3 cron ticks

            org.junit.jupiter.api.Assertions.assertEquals(afterCancel, submissions.get(),
                    "no tick may be submitted after destroy()");
        } finally {
            scheduler.shutdown();
        }
    }
}
