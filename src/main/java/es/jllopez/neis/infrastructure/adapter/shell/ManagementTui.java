package es.jllopez.neis.infrastructure.adapter.shell;

import es.jllopez.neis.application.AddressCatalogService;
import es.jllopez.neis.application.AiAssistantService;
import es.jllopez.neis.application.ChainService;
import es.jllopez.neis.application.ContentStoreService;
import es.jllopez.neis.application.ExecutionService;
import es.jllopez.neis.application.ManagementInfoService;
import es.jllopez.neis.application.NewsEventService;
import es.jllopez.neis.application.ScheduleProperties;
import es.jllopez.neis.domain.model.Address;
import es.jllopez.neis.domain.model.Chain;
import es.jllopez.neis.domain.model.ChainTask;
import es.jllopez.neis.domain.model.ContentRecord;
import es.jllopez.neis.domain.model.NewsEventItem;
import es.jllopez.neis.domain.model.TaskExecution;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * The unified management interface (US-16, issue #77): one screen from
 * which every management section of NEIS is reachable — the dashboard
 * (US-16.2), address management (US-09, the existing
 * {@link AddressTui} screen, re-entered from here), chain management
 * and runs (US-16.5 chain part / US-10 slice), execution management
 * with re-run (US-16.3), the news/events repository browser
 * (US-16.4) and the configuration overview (US-16.5).
 *
 * <p>Sections are numbered submenu entries of the main screen; the
 * current section and its state are always shown from live reads (the
 * address TUI's redraw contract, US-09.1). Interaction is consistent:
 * every destructive action confirms explicitly, every refused
 * operation prints its reason and leaves the state untouched, EOF
 * (piped input, Ctrl-D) and Ctrl-C exit cleanly — the screen is
 * script-verifiable like {@link AddressTui}.</p>
 *
 * <p>Thin over the application use cases: this class only reads state
 * and calls the services the CLI commands already call, so every
 * action here obeys the same system rules (eligibility gate US-04.3,
 * idempotent operations US-15.4, validation before saving US-12.3).</p>
 */
class ManagementTui {

    private static final DateTimeFormatter TS = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private final AddressCatalogService catalog;
    private final ChainService chains;
    private final ExecutionService executions;
    private final NewsEventService newsEvents;
    private final ContentStoreService contentStore;
    private final ManagementInfoService info;
    private final ScheduleProperties schedules;
    private final AiAssistantService ai;
    private final es.jllopez.neis.domain.port.SelectorStorePort selectorStore;
    private final ConsoleIO io;

    ManagementTui(AddressCatalogService catalog,
                  ChainService chains,
                  ExecutionService executions,
                  NewsEventService newsEvents,
                  ContentStoreService contentStore,
                  ManagementInfoService info,
                  ScheduleProperties schedules,
                  AiAssistantService ai,
                  es.jllopez.neis.domain.port.SelectorStorePort selectorStore,
                  ConsoleIO io) {
        this.catalog = catalog;
        this.chains = chains;
        this.executions = executions;
        this.newsEvents = newsEvents;
        this.contentStore = contentStore;
        this.info = info;
        this.schedules = schedules;
        this.ai = ai;
        this.selectorStore = selectorStore;
        this.io = io;
    }

    /** Runs the main menu loop until the user quits or input ends. */
    void run() {
        io.println("NEIS management (US-16) — unified system management. " +
                "The screens redraw from live state after every operation.");
        while (true) {
            ConsoleIO.printManagementScreen(io, dashboardText());
            String line = io.readLine("manage> ");
            if (line == null) {
                io.println("(input ended)");
                return;
            }
            switch (line.trim().toLowerCase()) {
                case "" -> { /* redraw */ }
                case "d", "dashboard" -> { /* the redraw IS the dashboard */ }
                case "a", "addresses" -> new AddressTui(catalog, io).run();
                case "c", "chains" -> chainsSection();
                case "e", "executions" -> executionsSection();
                case "r", "repository" -> repositorySection();
                case "g", "config" -> configSection();
                case "q", "quit", "exit" -> {
                    io.println("Bye.");
                    return;
                }
                default -> io.println("Unknown section '" + line.trim()
                        + "' — choose one of d/a/c/e/r/g/q.");
            }
        }
    }

    // ---- US-16.2: the dashboard is the main screen's content ----

    private String dashboardText() {
        ManagementInfoService.Dashboard d = info.dashboard();
        StringBuilder out = new StringBuilder();
        out.append("DASHBOARD\n");
        out.append("  AI integration : ").append(d.aiAvailable() ? "active" : "not configured")
                .append("\n");
        out.append("  Addresses      : ").append(d.addresses()).append(" registered (")
                .append(d.verifiedAddresses()).append(" verified, ")
                .append(d.unverifiedAddresses()).append(" unverified, ")
                .append(d.failedAddresses()).append(" failed)\n");
        out.append("  Chains         : ").append(d.chains()).append(" defined\n");
        out.append("  Executions     : ").append(d.executions()).append(" records (")
                .append(d.running()).append(" active, ").append(d.failed()).append(" failed)\n");
        TaskExecution last = d.lastExecution();
        if (last != null) {
            out.append("  Last run       : ").append(last.id()).append("  ")
                    .append(last.taskCode()).append(" over ")
                    .append(last.addressId() == null ? "-" : last.addressId())
                    .append("  ").append(last.status())
                    .append(last.finishedAt() == null ? " (running)" : "  " + TS.format(last.finishedAt()))
                    .append("\n");
        }
        out.append("  Repository     : ").append(d.storedItems()).append(" news/events stored\n");
        out.append("  Schedules      : ").append(d.schedules()).append(" configured\n");
        return out.toString();
    }

    // ---- US-16.3: execution management (history, detail, re-run) ----

    private void executionsSection() {
        while (true) {
            List<TaskExecution> records = executions.list();
            ConsoleIO.printSection(io, "EXECUTIONS (US-16.3)", executionsTable(records),
                    "[l]ist  [<n>] show detail  [r] re-run  [b]ack");
            String line = io.readLine("executions> ");
            if (line == null) {
                return;
            }
            String trimmed = line.trim().toLowerCase();
            if (trimmed.isEmpty() || trimmed.equals("l") || trimmed.equals("list")) {
                continue; // the redraw IS the list
            }
            switch (trimmed) {
                case "b", "back", "q", "quit", "exit" -> {
                    return;
                }
                case "r" -> reRun(records);
                default -> {
                    // a bare number shows that record's detail
                    Integer index = parseIndex(trimmed, records.size());
                    if (index == null) {
                        io.println("Unknown command '" + line.trim()
                                + "' — choose l / <n> / r / b.");
                        continue;
                    }
                    io.println(executionDetail(records.get(index)));
                }
            }
        }
    }

    private String executionsTable(List<TaskExecution> records) {
        StringBuilder out = new StringBuilder("Execution records (")
                .append(records.size()).append(", newest first):\n");
        int n = 1;
        for (TaskExecution e : records) {
            out.append("  ").append(n++).append(". ").append(e.id()).append("  ")
                    .append(String.format("%-10s", e.taskCode()))
                    .append("  ").append(String.format("%-8s",
                            e.addressId() == null ? "-" : e.addressId()))
                    .append("  ").append(String.format("%-9s", e.triggerType()))
                    .append("  ").append(String.format("%-7s", e.status()))
                    .append("  ").append(e.finishedAt() == null ? "(running)"
                            : TS.format(e.finishedAt()))
                    .append("\n");
        }
        if (records.isEmpty()) {
            out.append("  <no executions yet — run a task or chain from the Chains section>\n");
        }
        return out.toString();
    }

    /** Run detail (US-16.3): outcome, errors, produced content links. */
    private String executionDetail(TaskExecution e) {
        StringBuilder out = new StringBuilder();
        out.append("Execution ").append(e.id()).append("\n");
        out.append("  task     : ").append(e.taskCode()).append("\n");
        out.append("  address  : ").append(e.addressId() == null ? "-" : e.addressId()).append("\n");
        if (e.chainId() != null) {
            out.append("  chain    : ").append(e.chainId()).append("\n");
        }
        out.append("  trigger  : ").append(e.triggerType()).append("\n");
        out.append("  status   : ").append(e.status()).append("\n");
        out.append("  started  : ").append(e.startedAt() == null ? "-" : TS.format(e.startedAt())).append("\n");
        out.append("  finished : ").append(e.finishedAt() == null ? "-" : TS.format(e.finishedAt())).append("\n");
        out.append("  outcome  : ").append(e.outcome() == null ? "-" : e.outcome()).append("\n");
        if (e.addressId() != null) {
            List<ContentRecord> produced = contentStore.listByAddress(e.addressId());
            if (!produced.isEmpty()) {
                out.append("  content in the storage tree (US-11.4):\n");
                for (ContentRecord record : produced) {
                    out.append("    ").append(record.id())
                            .append("  [").append(record.taskCode()).append("]  ")
                            .append(record.path()).append("\n");
                }
            }
        }
        return out.toString();
    }

    /** Re-run (US-16.3): resubmits the same task or chain over the
     * same address. Idempotent by platform rules (US-15.4): the run
     * itself is idempotent, the new record is a separate attempt. */
    private void reRun(List<TaskExecution> records) {
        String line = io.readLine("  number of the execution to re-run: ");
        if (line == null) {
            return;
        }
        Integer index = parseIndex(line.trim(), records.size());
        if (index == null) {
            io.println("  Refused: pick a listed execution number (1-"
                    + records.size() + ").");
            return;
        }
        TaskExecution original = records.get(index);
        if (original.chainId() != null) {
            try {
                TaskExecution submitted = executions.submitChain(
                        original.chainId(), original.addressId(), es.jllopez.neis.domain.model.TriggerType.MANUAL);
                io.println("  Re-submitted chain run: " + submitted.id());
            } catch (IllegalArgumentException | IllegalStateException e) {
                io.println("  Refused: " + e.getMessage());
            }
            return;
        }
        try {
            TaskExecution submitted = executions.submit(
                    original.taskCode(), original.addressId(),
                    es.jllopez.neis.domain.model.TriggerType.MANUAL);
            io.println("  Re-submitted " + submitted.id() + ": "
                    + submitted.taskCode() + " over " + submitted.addressId());
        } catch (IllegalArgumentException | IllegalStateException e) {
            io.println("  Refused: " + e.getMessage());
        }
    }

    // ---- US-16.5 (chain part) / US-10 slice: chain management ----

    private void chainsSection() {
        while (true) {
            List<Chain> chainList = chains.list();
            ConsoleIO.printSection(io, "CHAINS (US-16.5)", chainsTable(chainList),
                    "[l]ist  [<n>] show  [r] run over an address  [b]ack");
            String line = io.readLine("chains> ");
            if (line == null) {
                return;
            }
            String trimmed = line.trim().toLowerCase();
            if (trimmed.isEmpty() || trimmed.equals("l") || trimmed.equals("list")) {
                continue;
            }
            switch (trimmed) {
                case "b", "back", "q", "quit", "exit" -> {
                    return;
                }
                case "r" -> runChain(chainList);
                default -> {
                    Integer index = parseIndex(trimmed, chainList.size());
                    if (index == null) {
                        io.println("Unknown command '" + line.trim()
                                + "' — choose l / <n> / r / b.");
                        continue;
                    }
                    io.println(chainDetail(chainList.get(index)));
                }
            }
        }
    }

    private String chainsTable(List<Chain> chainList) {
        StringBuilder out = new StringBuilder("Chains (").append(chainList.size()).append("):\n");
        int n = 1;
        for (Chain chain : chainList) {
            out.append("  ").append(n++).append(". ").append(chain.id())
                    .append("  '").append(chain.name()).append("'  ")
                    .append(chain.tasks().size()).append(" steps\n");
        }
        if (chainList.isEmpty()) {
            out.append("  <no chains yet — create one with 'chain create'>\n");
        }
        return out.toString();
    }

    private String chainDetail(Chain chain) {
        StringBuilder out = new StringBuilder("Chain ").append(chain.id())
                .append("  '").append(chain.name()).append("'\n");
        if (chain.description() != null && !chain.description().isBlank()) {
            out.append("  description: ").append(chain.description()).append("\n");
        }
        out.append("  created    : ").append(chain.createdAt() == null
                ? "-" : TS.format(chain.createdAt())).append("\n");
        for (ChainTask task : chain.tasks()) {
            out.append("  ").append(task.position()).append(". ")
                    .append(task.taskCode());
            if (!task.parameters().isEmpty()) {
                out.append("  ").append(task.parameters());
            }
            out.append("\n");
        }
        return out.toString();
    }

    private void runChain(List<Chain> chainList) {
        if (chainList.isEmpty()) {
            io.println("  Nothing to run — no chains defined.");
            return;
        }
        String chainLine = io.readLine("  chain number to run: ");
        if (chainLine == null) {
            return;
        }
        Integer chainIndex = parseIndex(chainLine.trim(), chainList.size());
        if (chainIndex == null) {
            io.println("  Refused: pick a listed chain number (1-"
                    + chainList.size() + ").");
            return;
        }
        Chain chain = chainList.get(chainIndex);
        List<Address> addresses = catalog.list();
        if (addresses.isEmpty()) {
            io.println("  Nothing to run over — no addresses registered.");
            return;
        }
        String addressLine = io.readLine("  address id: ");
        if (addressLine == null) {
            return;
        }
        String addressId = addressLine.trim();
        boolean known = addresses.stream().anyMatch(a -> a.id().equalsIgnoreCase(addressId));
        if (!known) {
            io.println("  Refused: unknown address '" + addressId + "'.");
            return;
        }
        try {
            TaskExecution submitted = executions.submitChain(chain.id(), addressId,
                    es.jllopez.neis.domain.model.TriggerType.MANUAL);
            io.println("  Submitted " + submitted.id() + ": chain " + chain.id()
                    + " over " + addressId + " (running asynchronously)");
            String wait = io.readLine("  wait for it? [y/N]: ");
            if (wait != null && wait.trim().equalsIgnoreCase("y")) {
                TaskExecution finished = executions.await(submitted.id());
                io.println(finished == null
                        ? "  still running after the wait timeout — check the Executions section."
                        : "  " + finished.status() + ": "
                        + (finished.outcome() == null ? "-" : finished.outcome()));
            }
        } catch (IllegalArgumentException | IllegalStateException e) {
            io.println("  Refused: " + e.getMessage());
        }
    }

    // ---- US-16.4: repository browsing (news/events + content tree) ----

    private void repositorySection() {
        while (true) {
            List<NewsEventItem> items = newsEvents.search(
                    new es.jllopez.neis.domain.model.NewsEventQuery(
                            null, null, null, null,
                            es.jllopez.neis.domain.model.NewsEventQuery.DEFAULT_LIMIT));
            ConsoleIO.printSection(io, "REPOSITORY (US-16.4)", repositoryTable(items),
                    "[l]ist news/events  [t]ree of stored content  [<n>] item detail  [b]ack");
            String line = io.readLine("repository> ");
            if (line == null) {
                return;
            }
            String trimmed = line.trim().toLowerCase();
            if (trimmed.isEmpty() || trimmed.equals("l") || trimmed.equals("list")) {
                continue;
            }
            switch (trimmed) {
                case "b", "back", "q", "quit", "exit" -> {
                    return;
                }
                case "t", "tree" -> io.println(contentTree());
                default -> {
                    Integer index = parseIndex(trimmed, items.size());
                    if (index == null) {
                        io.println("Unknown command '" + line.trim()
                                + "' — choose l / t / <n> / b.");
                        continue;
                    }
                    io.println(itemDetail(items.get(index)));
                }
            }
        }
    }

    private String repositoryTable(List<NewsEventItem> items) {
        StringBuilder out = new StringBuilder("News/events (").append(items.size())
                .append(" shown, newest first):\n");
        int n = 1;
        for (NewsEventItem item : items) {
            out.append("  ").append(n++).append(". ")
                    .append(item.date() == null ? "????-??-??" : item.date())
                    .append("  ").append(item.kind() == null ? "-" : item.kind())
                    .append("  [").append(item.addressId()).append("]  ")
                    .append(item.title() == null ? "<no title>" : item.title())
                    .append(item.incomplete() ? "  INCOMPLETE" : "")
                    .append("\n");
        }
        if (items.isEmpty()) {
            out.append("  <no stored news/events — run a chain over a verified address>\n");
        }
        return out.toString();
    }

    /** Item metadata (US-16.4): every field, with the mandatory-metadata
     * flag visible (US-14.4) and provenance (US-14.2). */
    private String itemDetail(NewsEventItem item) {
        StringBuilder out = new StringBuilder("News/event item\n");
        out.append("  title      : ").append(item.title() == null || item.title().isBlank()
                ? "<none>" : item.title()).append("\n");
        out.append("  kind       : ").append(item.kind()).append("\n");
        out.append("  date       : ").append(item.date() == null ? "<missing>" : item.date()).append("\n");
        out.append("  description: ").append(item.description() == null || item.description().isBlank()
                ? "<missing>" : item.description()).append("\n");
        out.append("  url        : ").append(item.url() == null ? "-" : item.url()).append("\n");
        out.append("  picture    : ").append(item.picture() == null ? "-" : item.picture()).append("\n");
        out.append("  location   : ").append(item.location() == null ? "-" : item.location()).append("\n");
        out.append("  address    : ").append(item.addressId()).append("\n");
        out.append("  provenance : execution ")
                .append(item.executionId() == null ? "-" : item.executionId())
                .append(", content ")
                .append(item.contentId() == null ? "-" : item.contentId()).append("\n");
        out.append("  completeness: ").append(item.incomplete()
                ? "INCOMPLETE (missing: " + (item.missingFields() == null ? "?" : item.missingFields()) + ")"
                : "complete").append("\n");
        return out.toString();
    }

    /** The storage tree (US-11.4): address / capture / task from the
     * content records, with each record's file path. */
    private String contentTree() {
        List<ContentRecord> records = contentStore.listAll();
        StringBuilder out = new StringBuilder("Content tree (").append(records.size())
                .append(" records), root: ").append(contentStore.root()).append("\n");
        String currentAddress = null;
        String currentCapture = null;
        for (ContentRecord record : records) {
            if (!record.addressId().equals(currentAddress)) {
                currentAddress = record.addressId();
                currentCapture = null;
                out.append("  ").append(currentAddress).append("/\n");
            }
            if (!record.captureId().equals(currentCapture)) {
                currentCapture = record.captureId();
                out.append("    ").append(currentCapture).append("/\n");
            }
            out.append("      content/").append(record.taskCode()).append("/  ")
                    .append(record.path()).append("\n");
        }
        if (records.isEmpty()) {
            out.append("  <no stored content yet>\n");
        }
        return out.toString();
    }

    // ---- US-16.5: configuration management section ----

    private void configSection() {
        while (true) {
            ConsoleIO.printSection(io, "CONFIGURATION (US-16.5)", configText(),
                    "[l]ist config  [s]electors of an address  [b]ack");
            String line = io.readLine("config> ");
            if (line == null) {
                return;
            }
            String trimmed = line.trim().toLowerCase();
            if (trimmed.isEmpty() || trimmed.equals("l") || trimmed.equals("list")) {
                continue;
            }
            switch (trimmed) {
                case "b", "back", "q", "quit", "exit" -> {
                    return;
                }
                case "s", "selectors" -> showSelectors();
                default -> io.println("Unknown command '" + line.trim()
                        + "' — choose l / s / b.");
            }
        }
    }

    /** The configuration overview (US-16.5): what is configured and
     * where it lives. Values are shown read-only here — the externalized
     * configuration (US-15.1) stays the source of record, edited in
     * application.yml; the section makes the effective state visible
     * and validates that what it shows is loadable. */
    private String configText() {
        StringBuilder out = new StringBuilder();
        out.append("  Work dir (content root): ").append(contentStore.root()).append("\n");
        out.append("  AI provider            : ").append(ai.isAvailable()
                ? "active" : "disabled (neis.ai.enabled=false)")
                .append("\n");
        out.append("  Schedules (").append(schedules.entries().size()).append("):\n");
        for (ScheduleProperties.Entry entry : schedules.entries()) {
            out.append("    ").append(entry.name()).append(": ")
                    .append(entry.task()).append(" over ").append(entry.address())
                    .append("  cron '").append(entry.cron()).append("'\n");
        }
        if (schedules.entries().isEmpty()) {
            out.append("    <none configured (neis.schedule.entries)>\n");
        }
        return out.toString();
    }

    /** The stored selectors and filter catalogue of one address
     * (US-16.5 selector part, US-12.1/12.6 visibility). */
    private void showSelectors() {
        String line = io.readLine("  address id: ");
        if (line == null) {
            return;
        }
        String addressId = line.trim();
        try {
            catalog.byId(addressId);
        } catch (IllegalArgumentException e) {
            io.println("  " + e.getMessage());
            return;
        }
        es.jllopez.neis.domain.model.StoredSelectors selectors =
                selectorStore.load(addressId).orElse(null);
        if (selectors == null) {
            io.println("  No stored selectors for " + addressId
                    + " — identified on the next ANALYZE.");
            return;
        }
        io.println(selectorDetail(selectors));
    }

    /** The selector store, read for the selectors view (US-16.5). */
    private String selectorDetail(es.jllopez.neis.domain.model.StoredSelectors selectors) {
        StringBuilder out = new StringBuilder("Stored selectors for ")
                .append(selectors.addressId()).append(" (").append(selectors.kind()).append("):\n");
        out.append("  item selector    : ").append(selectors.selector()).append("\n");
        out.append("  filter zone      : ").append(selectors.filterSelector() == null
                ? "-" : selectors.filterSelector()).append("\n");
        if (selectors.filters().isEmpty()) {
            out.append("  filter catalogue : <none>\n");
        } else {
            out.append("  filter catalogue :\n");
            for (es.jllopez.neis.domain.model.FilterDefinition filter : selectors.filters()) {
                out.append("    ").append(filter.name()).append(": ")
                        .append(filter.values()).append("\n");
            }
        }
        out.append("  discovered by    : ").append(selectors.discoveredBy())
                .append(" at ").append(selectors.discoveredAt()).append("\n");
        return out.toString();
    }

    // ---- shared helpers ----

    /** 1-based list index from a typed number; null when out of range
     * or not a number. */
    private static Integer parseIndex(String input, int size) {
        if (input.isEmpty() || size == 0) {
            return null;
        }
        int value;
        try {
            value = Integer.parseInt(input);
        } catch (NumberFormatException e) {
            return null;
        }
        if (value < 1 || value > size) {
            return null;
        }
        return value - 1;
    }
}
