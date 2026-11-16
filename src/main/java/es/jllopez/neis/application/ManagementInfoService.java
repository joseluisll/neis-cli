package es.jllopez.neis.application;

import es.jllopez.neis.domain.model.Address;
import es.jllopez.neis.domain.model.Chain;
import es.jllopez.neis.domain.model.ExecutionStatus;
import es.jllopez.neis.domain.model.TaskExecution;
import es.jllopez.neis.domain.model.VerificationStatus;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Use case behind the management UI's dashboard section (US-16.2,
 * issue #79): aggregates the state every other section works on into
 * one snapshot — catalog health (US-03/US-04), chain definitions
 * (US-08), execution records (US-05.3), repository size (US-14) and
 * the configured schedules (US-05.2).
 *
 * <p>The snapshot answers the acceptance questions directly: what ran
 * last AND what failed recently (the last failed records, so a
 * successful last run does not hide an earlier failure), what is
 * scheduled next (the next fire time computed from each entry's cron
 * expression), and what task types the executor accepts.</p>
 *
 * <p>Depends only on application use cases and domain ports — the TUI
 * adapter renders the snapshot, this class computes it. Every field is
 * recomputed from the live stores on each call, so a dashboard
 * redrawn by the TUI is never stale (the same contract as the address
 * TUI's redraw, US-09.1).</p>
 */
@Service
public class ManagementInfoService {

    private static final DateTimeFormatter FIRE_AT = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm");
    /** How many recent failed runs the dashboard surfaces. */
    private static final int RECENT_FAILURES = 3;

    private final AddressCatalogService catalog;
    private final ChainService chains;
    private final ExecutionService executions;
    private final NewsEventService newsEvents;
    private final ScheduleProperties schedules;
    private final AiAssistantService ai;

    public ManagementInfoService(AddressCatalogService catalog,
                                 ChainService chains,
                                 ExecutionService executions,
                                 NewsEventService newsEvents,
                                 ScheduleProperties schedules,
                                 AiAssistantService ai) {
        this.catalog = catalog;
        this.chains = chains;
        this.executions = executions;
        this.newsEvents = newsEvents;
        this.schedules = schedules;
        this.ai = ai;
    }

    /** One schedule entry with its computed next fire time. */
    public record ScheduleFire(String name, String task, String address,
                               String nextAt, String error) {
    }

    /** The dashboard snapshot: everything the main screen shows. */
    public record Dashboard(
            int addresses,
            int verifiedAddresses,
            int unverifiedAddresses,
            int failedAddresses,
            int chains,
            List<String> chainNames,
            List<String> taskTypes,
            long executions,
            long running,
            long failed,
            TaskExecution lastExecution,
            List<TaskExecution> recentFailures,
            long storedItems,
            List<ScheduleFire> nextRuns,
            boolean aiAvailable) {
    }

    /** Recomputes the dashboard from live state. */
    public Dashboard dashboard() {
        List<Address> addresses = catalog.list();
        int verified = 0;
        int unverified = 0;
        int failed = 0;
        for (Address address : addresses) {
            if (address.verificationStatus() == VerificationStatus.VERIFIED) {
                verified++;
            } else if (address.verificationStatus() == VerificationStatus.FAILED) {
                failed++;
            } else {
                unverified++;
            }
        }
        List<Chain> chainList = chains.list();
        List<String> chainNames = chainList.stream().map(Chain::name).toList();
        List<TaskExecution> executionList = executions.list();
        long running = executionList.stream()
                .filter(e -> e.status() == ExecutionStatus.RUNNING
                        || e.status() == ExecutionStatus.PENDING)
                .count();
        long failedRuns = executionList.stream()
                .filter(e -> e.status() == ExecutionStatus.FAILED)
                .count();
        return new Dashboard(addresses.size(), verified, unverified, failed,
                chainList.size(), chainNames, executions.taskTypes(),
                executionList.size(), running, failedRuns,
                executionList.isEmpty() ? null : executionList.get(0),
                executionList.stream()
                        .filter(e -> e.status() == ExecutionStatus.FAILED)
                        .limit(RECENT_FAILURES)
                        .toList(),
                newsEvents.count(), nextRuns(), ai.isAvailable());
    }

    /**
     * The next fire time of every configured schedule (US-16.2, issue
     * #79: "what is scheduled next"). Computed from each entry's cron
     * expression — the same expression {@link ScheduleRegistrar}
     * registered — so the dashboard and the registrar cannot disagree.
     * An unparseable cron is reported as an error entry instead of
     * being silently dropped (bad configuration must be visible).
     */
    private List<ScheduleFire> nextRuns() {
        ZonedDateTime now = ZonedDateTime.now();
        return schedules.entries().stream()
                .map(entry -> {
                    try {
                        CronExpression cron = CronExpression.parse(entry.cron());
                        ZonedDateTime next = cron.next(now);
                        return new ScheduleFire(entry.name(), entry.task(),
                                entry.address(),
                                next == null ? null : FIRE_AT.format(next),
                                null);
                    } catch (IllegalArgumentException e) {
                        return new ScheduleFire(entry.name(), entry.task(),
                                entry.address(), null,
                                "invalid cron '" + entry.cron() + "': "
                                        + e.getMessage());
                    }
                })
                .toList();
    }
}
