package es.jllopez.neis.application;

import es.jllopez.neis.domain.model.Address;
import es.jllopez.neis.domain.model.Chain;
import es.jllopez.neis.domain.model.TaskExecution;
import es.jllopez.neis.domain.model.VerificationStatus;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Use case behind the management UI's dashboard section (US-16.2,
 * issue #79): aggregates the state every other section works on into
 * one snapshot — catalog health (US-03/US-04), chain definitions
 * (US-08), execution records (US-05.3), repository size (US-14) and
 * the configured schedules (US-05.2).
 *
 * <p>Depends only on application use cases and domain ports — the TUI
 * adapter renders the snapshot, this class computes it. Every getter
 * re-reads the live stores, so a dashboard redrawn by the TUI is never
 * stale (the same contract as the address TUI's redraw, US-09.1).</p>
 */
@Service
public class ManagementInfoService {

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

    /** The dashboard snapshot: everything the main screen shows. */
    public record Dashboard(
            int addresses,
            int verifiedAddresses,
            int unverifiedAddresses,
            int failedAddresses,
            int chains,
            long executions,
            long running,
            long failed,
            TaskExecution lastExecution,
            long storedItems,
            int schedules,
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
        List<TaskExecution> executionList = executions.list();
        long running = executionList.stream()
                .filter(e -> e.status() == es.jllopez.neis.domain.model.ExecutionStatus.RUNNING
                        || e.status() == es.jllopez.neis.domain.model.ExecutionStatus.PENDING)
                .count();
        long failedRuns = executionList.stream()
                .filter(e -> e.status() == es.jllopez.neis.domain.model.ExecutionStatus.FAILED)
                .count();
        return new Dashboard(addresses.size(), verified, unverified, failed,
                chainList.size(), executionList.size(), running, failedRuns,
                executionList.isEmpty() ? null : executionList.get(0),
                newsEvents.count(), schedules.entries().size(), ai.isAvailable());
    }

    /** The last execution record (dashboard link target), or null. */
    public TaskExecution lastExecution() {
        List<TaskExecution> all = executions.list();
        return all.isEmpty() ? null : all.get(0);
    }
}
