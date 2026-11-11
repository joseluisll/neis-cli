package es.jllopez.neis.application;

import es.jllopez.neis.domain.model.TriggerType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Component;

import java.time.Instant;

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
 */
@Component
public class ScheduleRegistrar {

    private static final Logger log = LoggerFactory.getLogger(ScheduleRegistrar.class);

    public ScheduleRegistrar(TaskScheduler taskScheduler,
                             ExecutionService executions,
                             ScheduleProperties properties) {
        for (ScheduleProperties.Entry entry : properties.entries()) {
            taskScheduler.schedule(() -> fire(executions, entry),
                    new CronTrigger(entry.cron()));
            log.info("Scheduled '{}': {} over {} at cron '{}'",
                    entry.name(), entry.task(), entry.address(), entry.cron());
        }
        log.info("Schedule registrar: {} entries active", properties.entries().size());
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
