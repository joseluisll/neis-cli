package es.jllopez.neis.application;

import es.jllopez.neis.domain.model.TriggerType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledFuture;

/**
 * Registers every configured schedule entry (US-05.2, issue #25) as a
 * cron trigger over the task scheduler. When a trigger fires, the
 * entry's task is submitted to the asynchronous executor with the
 * SCHEDULED trigger type — the run itself happens off the scheduler
 * thread and leaves its execution record like any manual run.
 *
 * <p>A refused submission (unknown task, unknown or ineligible
 * address, US-04.3) is logged and does not cancel the schedule: the
 * next tick retries, and the operator fixes the configuration or the
 * address in the meantime.</p>
 *
 * <p>The registrar keeps the {@link ScheduledFuture} handle of every
 * trigger it registers and cancels them all on context close
 * (review finding, 2026-11-11): without the cancel path a context
 * refresh would re-register the same entries over a surviving
 * scheduler, doubling every tick.</p>
 */
@Component
public class ScheduleRegistrar implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(ScheduleRegistrar.class);

    private final List<ScheduledFuture<?>> handles = new ArrayList<>();

    public ScheduleRegistrar(TaskScheduler taskScheduler,
                             ExecutionService executions,
                             ScheduleProperties properties) {
        try {
            for (ScheduleProperties.Entry entry : properties.entries()) {
                handles.add(taskScheduler.schedule(() -> fire(executions, entry),
                        new CronTrigger(entry.cron())));
                log.info("Scheduled '{}': {} over {} at cron '{}'",
                        entry.name(), entry.task(), entry.address(), entry.cron());
            }
        } catch (RuntimeException e) {
            // A bad entry must not leave earlier entries ticking on a
            // registrar the container will never destroy (construction
            // failed, so destroy() is never called).
            cancelAll();
            throw e;
        }
        log.info("Schedule registrar: {} entries active", properties.entries().size());
    }

    @Override
    public void destroy() {
        cancelAll();
    }

    private void cancelAll() {
        if (handles.isEmpty()) {
            return;
        }
        int cancelled = 0;
        for (ScheduledFuture<?> handle : handles) {
            // false: let a tick that is already running finish; it
            // only prevents the NEXT fire.
            if (handle.cancel(false)) {
                cancelled++;
            }
        }
        handles.clear();
        log.info("Schedule registrar: {} schedule(s) cancelled", cancelled);
    }

    private void fire(ExecutionService executions, ScheduleProperties.Entry entry) {
        try {
            var record = executions.submit(entry.task(), entry.address(),
                    TriggerType.SCHEDULED);
            log.info("Scheduled run '{}' ticked: execution {} submitted at {}",
                    entry.name(), record.id(), Instant.now());
        } catch (RuntimeException e) {
            log.warn("Scheduled run '{}' refused: {} (the schedule stays active)",
                    entry.name(), e.toString());
        }
    }
}
