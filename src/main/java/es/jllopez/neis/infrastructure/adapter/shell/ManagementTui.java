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

import java.nio.file.Path;
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
    private final es.jllopez.neis.application.PreconfiguredSelectorProperties preconfiguredSelectors;
    private final es.jllopez.neis.application.PreconfiguredFilterProperties preconfiguredFilters;
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
                  es.jllopez.neis.application.PreconfiguredSelectorProperties preconfiguredSelectors,
                  es.jllopez.neis.application.PreconfiguredFilterProperties preconfiguredFilters,
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
        this.preconfiguredSelectors = preconfiguredSelectors;
        this.preconfiguredFilters = preconfiguredFilters;
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
                case "a", "addresses" -> new AddressTui(catalog, io, true).run();
                case "c", "chains" -> chainsSection();
                case "e", "executions" -> executionsSection();
                case "r", "repository" -> repositorySection();
                case "n", "g", "config" -> configSection();
                case "o", "logs" -> logsSection();
                case "q", "quit", "exit" -> {
                    io.println("Bye.");
                    return;
                }
                default -> io.println("Unknown section '" + line.trim()
                        + "' — choose one of d/a/c/e/r/n/o/q.");
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
        out.append("  Chains         : ").append(d.chains()).append(" defined");
        if (!d.chainNames().isEmpty()) {
            out.append(" — ").append(String.join(", ", d.chainNames()));
        }
        out.append("\n");
        out.append("  Task types     : ").append(String.join(", ", d.taskTypes())).append("\n");
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
        for (TaskExecution failure : d.recentFailures()) {
            out.append("  Failed run     : ").append(failure.id()).append("  ")
                    .append(failure.taskCode()).append(" over ")
                    .append(failure.addressId() == null ? "-" : failure.addressId())
                    .append(failure.finishedAt() == null ? "" : "  " + TS.format(failure.finishedAt()))
                    .append("\n          ")
                    .append(failure.outcome() == null ? "(no outcome recorded)"
                            : firstLine(failure.outcome()))
                    .append("\n");
        }
        out.append("  Repository     : ").append(d.storedItems()).append(" news/events stored\n");
        if (d.nextRuns().isEmpty()) {
            out.append("  Schedules      : none configured (neis.schedule.entries)\n");
        } else {
            out.append("  Schedules      : ").append(d.nextRuns().size()).append(" configured\n");
            for (ManagementInfoService.ScheduleFire fire : d.nextRuns()) {
                out.append("    ").append(fire.name()).append(": ")
                        .append(fire.task()).append(" over ").append(fire.address())
                        .append(" — next run ").append(fire.error() != null
                                ? fire.error() + " (fix the cron to schedule it)"
                                : fire.nextAt() == null ? "never (cron matches nothing)"
                                : fire.nextAt())
                        .append("\n");
            }
        }
        return out.toString();
    }

    /** The first line of a multi-line outcome (the dashboard keeps one
     * line per entry; the full text is in the executions section). */
    private static String firstLine(String text) {
        int newline = text.indexOf('\n');
        return newline < 0 ? text : text.substring(0, newline);
    }

    // ---- US-16.1: the Logs section (issue #78) ----

    /** The rolling log's location, resolved like core/run.sh does
     * (NEIS_LOG_PATH -> neis.log.path -> ./logs/neis.log). */
    private Path logFile() {
        return Path.of(System.getProperty("neis.log.path", "./logs/neis.log"));
    }

    private void logsSection() {
        while (true) {
            ConsoleIO.printSection(io, "LOGS (US-16.1)", logsTable(),
                    "[l]ist tail  [<n>] last <n> lines  [b]ack");
            String line = io.readLine("logs> ");
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
                default -> {
                    Integer lines = parseCount(trimmed, 1, 1000);
                    if (lines == null) {
                        io.println("Unknown command '" + line.trim()
                                + "' — choose l / <n> (lines, 1-1000) / b.");
                        continue;
                    }
                    io.println(logTail(lines));
                }
            }
        }
    }

    private String logsTable() {
        Path log = logFile();
        return "Log file: " + log.toAbsolutePath()
                + (java.nio.file.Files.isRegularFile(log) ? " (" + sizeOf(log) + " bytes)"
                : " (not created yet — the file appears when NEIS first logs)");
    }

    private static long sizeOf(Path file) {
        try {
            return java.nio.file.Files.size(file);
        } catch (java.io.IOException e) {
            return -1;
        }
    }

    /** The last <lines> lines of the rolling log, oldest of them
     * first (log order). Missing file and read errors are reported
     * loud, never as an empty screen. */
    private String logTail(int lines) {
        Path log = logFile();
        if (!java.nio.file.Files.isRegularFile(log)) {
            return "No log file at " + log.toAbsolutePath()
                    + " — NEIS logs there once configured and run.";
        }
        try {
            List<String> all = java.nio.file.Files.readAllLines(log);
            int from = Math.max(0, all.size() - lines);
            StringBuilder out = new StringBuilder("Last ")
                    .append(all.size() - from).append(" of ")
                    .append(all.size()).append(" lines:\n");
            for (int i = from; i < all.size(); i++) {
                out.append("  ").append(all.get(i)).append("\n");
            }
            return out.toString();
        } catch (java.io.IOException e) {
            return "Cannot read " + log.toAbsolutePath() + ": " + e.getMessage();
        }
    }

    // ---- US-16.3: execution management (history, detail, re-run) ----

    /** The active filter of the executions section, or null. */
    private record ExecutionFilter(String addressId, String taskCode,
                                   String status, String dateFrom) {

        /** Whether the record passes every active dimension. */
        boolean accepts(TaskExecution e) {
            if (addressId != null
                    && (e.addressId() == null || !e.addressId().equalsIgnoreCase(addressId))) {
                return false;
            }
            if (taskCode != null
                    && (e.taskCode() == null || !e.taskCode().equalsIgnoreCase(taskCode))) {
                return false;
            }
            if (status != null && (e.status() == null || !e.status().name().equalsIgnoreCase(status))) {
                return false;
            }
            if (dateFrom != null) {
                String day = e.startedAt() == null ? null
                        : e.startedAt().atZone(ZoneId.systemDefault())
                        .toLocalDate().toString();
                if (day == null || day.compareTo(dateFrom) < 0) {
                    return false;
                }
            }
            return true;
        }

        /** Human description for the section header. */
        String describe() {
            List<String> parts = new java.util.ArrayList<>();
            if (addressId != null) {
                parts.add("address=" + addressId);
            }
            if (taskCode != null) {
                parts.add("task=" + taskCode);
            }
            if (status != null) {
                parts.add("status=" + status);
            }
            if (dateFrom != null) {
                parts.add("from=" + dateFrom);
            }
            return String.join(", ", parts);
        }
    }

    private void executionsSection() {
        ExecutionFilter filter = null;
        while (true) {
            List<TaskExecution> all = executions.list();
            List<TaskExecution> records = filterRecords(all, filter);
            String header = "Execution records (" + records.size()
                    + (filter == null ? " of " + all.size() + ", newest first)"
                    : " of " + all.size() + " match " + filter.describe() + ")");
            ConsoleIO.printSection(io, "EXECUTIONS (US-16.3)", header + ":\n"
                            + executionsTable(records),
                    "[l]ist  [f] filter (address/task/status/from-date, empty = clear)"
                            + "  [<n>] show detail  [r] re-run  [b]ack");
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
                case "f", "filter" -> filter = askExecutionFilter();
                case "r" -> reRun(records);
                default -> {
                    if (records.isEmpty()) {
                        io.println("  Nothing listed" + (filter == null ? ""
                                : " (filter: " + filter.describe() + ")")
                                + " — clear the filter with 'f' or list with 'l'.");
                        continue;
                    }
                    Integer index = parseIndex(trimmed, records.size());
                    if (index == null) {
                        io.println("Unknown command '" + line.trim()
                                + "' — choose l / f / <n> / r / b.");
                        continue;
                    }
                    io.println(executionDetail(records.get(index)));
                }
            }
        }
    }

    /** Asks the four filter dimensions (US-16.3: address, task/chain,
     * status, date); EMPTY input on a dimension keeps it unset, and an
     * entirely empty answer CLEARS the active filter. Values are
     * validated against what exists (statuses, task types, known
     * addresses) so a typo is refused instead of filtering everything
     * away silently. */
    private ExecutionFilter askExecutionFilter() {
        String address = io.readLine("  address id (empty = any): ");
        if (address == null) {
            return null;
        }
        address = address.trim();
        if (!address.isEmpty()) {
            try {
                catalog.byId(address);
            } catch (IllegalArgumentException e) {
                io.println("  Filter cleared — " + e.getMessage());
                return null;
            }
        }
        String task = io.readLine("  task code (empty = any): ");
        if (task == null) {
            return null;
        }
        task = task.trim().toUpperCase();
        if (!task.isEmpty() && !executions.taskTypes().contains(task)) {
            io.println("  Filter cleared — unknown task type '" + task
                    + "' (known: " + String.join(", ", executions.taskTypes()) + ").");
            return null;
        }
        String status = io.readLine("  status PENDING/RUNNING/SUCCESS/FAILED (empty = any): ");
        if (status == null) {
            return null;
        }
        status = status.trim().toUpperCase();
        if (!status.isEmpty() && !isValidStatus(status)) {
            io.println("  Filter cleared — unknown status '" + status
                    + "' (PENDING, RUNNING, SUCCESS or FAILED).");
            return null;
        }
        String from = io.readLine("  started on/after, ISO yyyy-MM-dd (empty = any): ");
        if (from == null) {
            return null;
        }
        from = from.trim();
        if (!from.isEmpty() && !from.matches("\\d{4}-\\d{2}-\\d{2}")) {
            io.println("  Filter cleared — the date must be ISO yyyy-MM-dd, got '"
                    + from + "'.");
            return null;
        }
        if (address.isEmpty() && task.isEmpty() && status.isEmpty() && from.isEmpty()) {
            io.println("  Filter cleared — showing every execution again.");
            return null;
        }
        return new ExecutionFilter(address.isEmpty() ? null : address,
                task.isEmpty() ? null : task,
                status.isEmpty() ? null : status,
                from.isEmpty() ? null : from);
    }

    private static boolean isValidStatus(String status) {
        for (es.jllopez.neis.domain.model.ExecutionStatus candidate :
                es.jllopez.neis.domain.model.ExecutionStatus.values()) {
            if (candidate.name().equalsIgnoreCase(status)) {
                return true;
            }
        }
        return false;
    }

    /** The records passing the filter (null filter = all). */
    private static List<TaskExecution> filterRecords(List<TaskExecution> all,
                                                     ExecutionFilter filter) {
        if (filter == null) {
            return all;
        }
        return all.stream().filter(filter::accepts).toList();
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
        if (!e.parameters().isEmpty()) {
            out.append("  params   : ").append(e.parameters()).append("\n");
        }
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
     * same address with the ORIGINAL parameters and trigger type (the
     * V12-persisted parameter map — a re-run that silently dropped the
     * parameters or downgraded a SCHEDULED trigger to MANUAL would not
     * be a faithful re-run; US-16.3 review finding #97). Only FAILED
     * or finished (SUCCESS) records are re-runnable (US-16.3); a
     * PENDING/RUNNING record is refused — it is still in flight, and
     * re-running it would queue a duplicate. Idempotent by platform
     * rules (US-15.4): the run itself is idempotent, the new record is
     * a separate attempt. */
    private void reRun(List<TaskExecution> records) {
        if (records.isEmpty()) {
            io.println("  Nothing to re-run — no executions listed.");
            return;
        }
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
        if (original.status() == es.jllopez.neis.domain.model.ExecutionStatus.PENDING
                || original.status() == es.jllopez.neis.domain.model.ExecutionStatus.RUNNING) {
            io.println("  Refused: " + original.id() + " is still "
                    + original.status() + " — wait for it to finish, then re-run.");
            return;
        }
        if (original.chainId() != null) {
            try {
                TaskExecution submitted = executions.submitChain(
                        original.chainId(), original.addressId(), original.triggerType());
                io.println("  Re-submitted chain run: " + submitted.id());
            } catch (IllegalArgumentException | IllegalStateException e) {
                io.println("  Refused: " + e.getMessage());
            }
            return;
        }
        try {
            TaskExecution submitted = executions.submit(
                    original.taskCode(), original.addressId(),
                    original.triggerType(), original.parameters());
            io.println("  Re-submitted " + submitted.id() + ": "
                    + submitted.taskCode() + " over " + submitted.addressId()
                    + " (" + submitted.triggerType() + ", same parameters)");
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
                    if (chainList.isEmpty()) {
                        io.println("  Nothing listed — no chains defined yet; "
                                + "create one with 'chain create'.");
                        continue;
                    }
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
        String kind = null;
        String addressId = null;
        Boolean complete = null; // null = any, true = complete only, false = incomplete only
        int limit = es.jllopez.neis.domain.model.NewsEventQuery.DEFAULT_LIMIT;
        while (true) {
            List<NewsEventItem> searched = newsEvents.search(
                    new es.jllopez.neis.domain.model.NewsEventQuery(
                            addressId, kind, null, null, limit));
            List<NewsEventItem> items = complete == null ? searched
                    : filterByCompleteness(searched, complete);
            String header = repositoryHeader(items, kind, addressId, complete, limit);
            ConsoleIO.printSection(io, "REPOSITORY (US-16.4)", header + ":\n"
                            + repositoryTable(items),
                    "[l]ist  [f] filter (kind/address/completeness/limit)  [t] tree of stored"
                            + " content  [<n>] item detail  [b]ack");
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
                case "t", "tree" -> browseContentTree();
                case "f", "filter" -> {
                    String[] filter = askRepositoryFilter(kind, addressId, complete, limit);
                    if (filter != null) {
                        kind = filter[0].isEmpty() ? null : filter[0];
                        addressId = filter[1].isEmpty() ? null : filter[1];
                        // 'complete' = show complete items only; the filter
                        // below keeps items whose incomplete flag differs
                        complete = filter[2].isEmpty() ? null
                                : !filter[2].equals("incomplete");
                        limit = Integer.parseInt(filter[3]);
                    }
                }
                default -> {
                    if (items.isEmpty()) {
                        io.println("  Nothing listed — adjust the filter with 'f' or list with 'l'.");
                        continue;
                    }
                    Integer index = parseIndex(trimmed, items.size());
                    if (index == null) {
                        io.println("Unknown command '" + line.trim()
                                + "' — choose l / f / t / <n> / b.");
                        continue;
                    }
                    io.println(itemDetail(items.get(index)));
                }
            }
        }
    }

    /** The items passing the completeness filter (null = all). */
    private static List<NewsEventItem> filterByCompleteness(List<NewsEventItem> items,
                                                            Boolean complete) {
        return items.stream()
                .filter(item -> item.incomplete() != complete)
                .toList();
    }

    private String repositoryHeader(List<NewsEventItem> items, String kind,
                                    String addressId, Boolean complete, int limit) {
        StringBuilder out = new StringBuilder("News/events (").append(items.size())
                .append(" shown, limit ").append(limit)
                .append(items.size() == limit ? " — more may exist, raise the limit" : "")
                .append(")");
        List<String> parts = new java.util.ArrayList<>();
        if (kind != null) {
            parts.add("kind=" + kind);
        }
        if (addressId != null) {
            parts.add("address=" + addressId);
        }
        if (complete != null) {
            parts.add(complete ? "complete only" : "incomplete only");
        }
        if (!parts.isEmpty()) {
            out.append(" — filter: ").append(String.join(", ", parts));
        }
        return out.toString();
    }

    /** Asks the repository filter dimensions (US-16.4: type, address,
     * completeness); EMPTY input keeps a dimension, an entirely empty
     * answer clears the filter. Values are validated (kind, known
     * address) so a typo is refused loud. */
    private String[] askRepositoryFilter(String kind, String addressId,
                                         Boolean complete, int limit) {
        String newKind = io.readLine("  kind news/event (empty = keep"
                + (kind == null ? " any" : " " + kind) + "): ");
        if (newKind == null) {
            return null;
        }
        newKind = newKind.trim().toLowerCase();
        if (!newKind.isEmpty() && !newKind.equals("news") && !newKind.equals("event")) {
            io.println("  Filter unchanged — the kind must be news or event, got '"
                    + newKind + "'.");
            return null;
        }
        String newAddress = io.readLine("  address id (empty = keep"
                + (addressId == null ? " any" : " " + addressId) + "): ");
        if (newAddress == null) {
            return null;
        }
        newAddress = newAddress.trim();
        if (!newAddress.isEmpty()) {
            try {
                catalog.byId(newAddress);
            } catch (IllegalArgumentException e) {
                io.println("  Filter unchanged — " + e.getMessage());
                return null;
            }
        }
        String newComplete = io.readLine("  completeness all/complete/incomplete (empty = keep "
                + (complete == null ? "all" : complete ? "complete" : "incomplete") + "): ");
        if (newComplete == null) {
            return null;
        }
        newComplete = newComplete.trim().toLowerCase();
        if (!newComplete.isEmpty() && !newComplete.equals("all")
                && !newComplete.equals("complete") && !newComplete.equals("incomplete")) {
            io.println("  Filter unchanged — completeness must be all, complete or incomplete, got '"
                    + newComplete + "'.");
            return null;
        }
        String newLimit = io.readLine("  limit, 1-1000 (empty = keep " + limit + "): ");
        if (newLimit == null) {
            return null;
        }
        newLimit = newLimit.trim();
        Integer parsedLimit = newLimit.isEmpty()
                ? limit : parseCount(newLimit, 1, 1000);
        if (parsedLimit == null) {
            io.println("  Filter unchanged — the limit must be a number 1-1000, got '"
                    + newLimit + "'.");
            return null;
        }
        if (newKind.isEmpty() && newAddress.isEmpty()
                && newComplete.isEmpty() && newLimit.isEmpty()) {
            io.println("  Filter cleared — showing everything (default limit).");
            return new String[]{"", "", "", String.valueOf(
                    es.jllopez.neis.domain.model.NewsEventQuery.DEFAULT_LIMIT)};
        }
        return new String[]{
                newKind.isEmpty() ? (kind == null ? "" : kind) : newKind,
                newAddress.isEmpty() ? (addressId == null ? "" : addressId) : newAddress,
                newComplete.isEmpty() ? (complete == null ? "" : complete ? "complete" : "incomplete") : newComplete,
                String.valueOf(parsedLimit)};
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

    /** The storage tree (US-11.4) rendered as a navigable drill-down
     * (US-16.4): the overview lists the addresses; typing a number
     * drills into its captures; the next number drills into that
     * capture's task folders; the next into the files; a file number
     * PREVIEWS its stored content (raw or simplified HTML5 rendered as
     * text/structure). 'u' goes one level up, 'b' back to the
     * repository menu. */
    private void browseContentTree() {
        List<ContentRecord> records = contentStore.listAll();
        if (records.isEmpty()) {
            io.println("Content tree: <no stored content yet>, root: "
                    + contentStore.root());
            return;
        }
        List<String> addresses = records.stream().map(ContentRecord::addressId)
                .distinct().toList();
        String selectedAddress = null;
        String selectedCapture = null;
        String selectedTask = null;
        while (true) {
            ConsoleIO.printSection(io, "CONTENT TREE (US-11.4), root: " + contentStore.root(),
                    treeLevel(records, addresses, selectedAddress, selectedCapture, selectedTask),
                    treeMenu(selectedAddress, selectedCapture, selectedTask));
            String line = io.readLine("tree> ");
            if (line == null) {
                return;
            }
            String trimmed = line.trim().toLowerCase();
            if (trimmed.isEmpty()) {
                continue;
            }
            switch (trimmed) {
                case "b", "back", "q", "quit", "exit" -> {
                    return;
                }
                case "u", "up" -> {
                    if (selectedTask != null) {
                        selectedTask = null;
                    } else if (selectedCapture != null) {
                        selectedCapture = null;
                    } else if (selectedAddress != null) {
                        selectedAddress = null;
                    }
                }
                default -> {
                    // navigation depends on the current level
                    if (selectedAddress == null) {
                        Integer index = parseIndex(trimmed, addresses.size());
                        if (index == null) {
                            io.println("Unknown command '" + line.trim()
                                    + "' — choose <n> (address) / b.");
                            continue;
                        }
                        selectedAddress = addresses.get(index);
                    } else if (selectedCapture == null) {
                        List<String> captures = capturesOf(records, selectedAddress);
                        Integer index = parseIndex(trimmed, captures.size());
                        if (index == null) {
                            io.println("Unknown command '" + line.trim()
                                    + "' — choose <n> (capture) / u / b.");
                            continue;
                        }
                        selectedCapture = captures.get(index);
                    } else if (selectedTask == null) {
                        List<String> tasks = tasksOf(records, selectedAddress, selectedCapture);
                        Integer index = parseIndex(trimmed, tasks.size());
                        if (index == null) {
                            io.println("Unknown command '" + line.trim()
                                    + "' — choose <n> (task folder) / u / b.");
                            continue;
                        }
                        selectedTask = tasks.get(index);
                    } else {
                        List<ContentRecord> files = filesOf(records, selectedAddress,
                                selectedCapture, selectedTask);
                        Integer index = parseIndex(trimmed, files.size());
                        if (index == null) {
                            io.println("Unknown command '" + line.trim()
                                    + "' — choose <n> (file, preview) / u / b.");
                            continue;
                        }
                        io.println(contentPreview(files.get(index)));
                    }
                }
            }
        }
    }

    private String treeMenu(String address, String capture, String task) {
        if (address == null) {
            return "[<n>] open address  [b]ack";
        }
        if (capture == null) {
            return "[<n>] open capture  [u]p  [b]ack";
        }
        if (task == null) {
            return "[<n>] open task folder  [u]p  [b]ack";
        }
        return "[<n>] preview file  [u]p  [b]ack";
    }

    /** The state lines of one tree level. */
    private String treeLevel(List<ContentRecord> records, List<String> addresses,
                             String address, String capture, String task) {
        StringBuilder out = new StringBuilder();
        if (address == null) {
            out.append("Addresses with stored content (").append(addresses.size()).append("):\n");
            for (int i = 0; i < addresses.size(); i++) {
                final String id = addresses.get(i);
                long count = records.stream()
                        .filter(r -> r.addressId().equals(id)).count();
                out.append("  ").append(i + 1).append(". ").append(id)
                        .append("/  (").append(count).append(" files)\n");
            }
            return out.toString();
        }
        if (capture == null) {
            List<String> captures = capturesOf(records, address);
            out.append(address).append("/ — captures (").append(captures.size()).append("):\n");
            for (int i = 0; i < captures.size(); i++) {
                final String id = captures.get(i);
                long count = records.stream()
                        .filter(r -> r.addressId().equals(address)
                                && r.captureId().equals(id)).count();
                out.append("  ").append(i + 1).append(". ").append(id)
                        .append("/  (").append(count).append(" files)\n");
            }
            return out.toString();
        }
        if (task == null) {
            List<String> tasks = tasksOf(records, address, capture);
            out.append(address).append("/").append(capture).append("/content/ — tasks (")
                    .append(tasks.size()).append("):\n");
            for (int i = 0; i < tasks.size(); i++) {
                final String id = tasks.get(i);
                long count = records.stream()
                        .filter(r -> r.addressId().equals(address)
                                && r.captureId().equals(capture)
                                && r.taskCode().equals(id)).count();
                out.append("  ").append(i + 1).append(". ").append(id)
                        .append("/  (").append(count).append(" files)\n");
            }
            return out.toString();
        }
        List<ContentRecord> files = filesOf(records, address, capture, task);
        out.append(address).append("/").append(capture).append("/content/")
                .append(task).append("/ — files (").append(files.size()).append("):\n");
        for (int i = 0; i < files.size(); i++) {
            out.append("  ").append(i + 1).append(". ")
                    .append(Path.of(files.get(i).path()).getFileName())
                    .append("\n        ").append(files.get(i).path()).append("\n");
        }
        return out.toString();
    }

    private static List<String> capturesOf(List<ContentRecord> records, String address) {
        return records.stream()
                .filter(r -> r.addressId().equals(address))
                .map(ContentRecord::captureId).distinct().toList();
    }

    private static List<String> tasksOf(List<ContentRecord> records, String address,
                                        String capture) {
        return records.stream()
                .filter(r -> r.addressId().equals(address) && r.captureId().equals(capture))
                .map(ContentRecord::taskCode).distinct().toList();
    }

    private static List<ContentRecord> filesOf(List<ContentRecord> records, String address,
                                               String capture, String task) {
        return records.stream()
                .filter(r -> r.addressId().equals(address) && r.captureId().equals(capture)
                        && r.taskCode().equals(task))
                .toList();
    }

    /** Content preview (US-16.4): reads the stored file and renders it
     * as text/structure — HTML5 files are stripped to their visible
     * text and structural tags (max ~100 lines); other files are shown
     * raw (capped). A missing or unreadable file is reported loud. */
    private String contentPreview(ContentRecord record) {
        Path file = Path.of(record.path());
        if (!java.nio.file.Files.isRegularFile(file)) {
            return "Preview unavailable — the file is gone from the storage tree: "
                    + record.path();
        }
        try {
            String raw = java.nio.file.Files.readString(file);
            String text = record.path().endsWith(".html") || raw.trim().startsWith("<")
                    ? htmlToText(raw) : raw;
            String[] lines = text.split("\\R");
            StringBuilder out = new StringBuilder("Preview of ").append(record.id())
                    .append(" [").append(record.taskCode()).append("] ")
                    .append(file.getFileName())
                    .append(" (").append(lines.length).append(" lines total):\n");
            int shown = Math.min(lines.length, 100);
            for (int i = 0; i < shown; i++) {
                String line = lines[i];
                if (line.length() > 160) {
                    line = line.substring(0, 160) + "…";
                }
                out.append("  ").append(line).append("\n");
            }
            if (lines.length > shown) {
                out.append("  … (").append(lines.length - shown)
                        .append(" more lines — open the file at ").append(record.path())
                        .append(")\n");
            }
            return out.toString();
        } catch (java.io.IOException e) {
            return "Cannot read " + record.path() + ": " + e.getMessage();
        }
    }

    /** Renders stored HTML5 as text/structure: script/style/noscript
     * dropped, block boundaries become newlines, tags stripped,
     * entities unescaped, whitespace collapsed. Pure string work —
     * the jsoup dependency stays behind the ports (US-01.4). */
    private static String htmlToText(String html) {
        String working = html
                .replaceAll("(?is)<(script|style|noscript|svg|head)\\b.*?</\\1>", "")
                .replaceAll("(?is)<br\\s*/?>", "\n")
                .replaceAll("(?is)</(p|div|li|tr|h[1-6]|section|article|header|footer|table|ul|ol|select|option)>", "\n")
                .replaceAll("(?is)<[^>]+>", "")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'");
        return java.util.Arrays.stream(working.split("\\R"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(java.util.stream.Collectors.joining("\n"));
    }

    /** The flat tree rendering (kept for the execution detail's
     * content links context); the navigable view is
     * {@link #browseContentTree()}. */
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

    /** The configuration overview (US-16.5): every configurable element
     * the system exposes, shown read-only — the externalized
     * configuration (US-15.1, application.yml) stays the source of
     * record, edited there; the section makes the effective state
     * visible. Editing from the TUI is a later slice (tracked in the
     * review-findings issue #97). */
    private String configText() {
        StringBuilder out = new StringBuilder();
        out.append("General settings (US-15.1):\n");
        out.append("  Work dir (content root) : ").append(contentStore.root()).append("\n");
        out.append("  Database                : ").append(System.getProperty("neis.db.path", "./neis.db")).append("\n");
        out.append("  Log file                : ").append(logFile().toAbsolutePath()).append("\n");
        out.append("  Scrape connector        : ").append(System.getProperty("neis.scrape.connector", "default (per webpage type)")).append("\n");
        out.append("  Extraction limit        : ").append(System.getProperty("neis.extraction.max-items", "(unset — no global cap)")).append(" (US-12.5 neis.extraction.max-items)\n");
        out.append("  Verification timeout    : ").append(System.getProperty("neis.address.verification.timeout-seconds", "(default)")).append(" s\n");
        out.append("AI provider (US-15.3):\n");
        out.append("  Status                  : ").append(ai.isAvailable() ? "active" : "disabled (neis.ai.enabled=false)").append("\n");
        out.append("  Provider / model        : ").append(System.getProperty("neis.ai.provider", "melious")).append(" / ")
                .append(System.getProperty("neis.ai.model", "(default)")).append("\n");
        out.append("  Base URL                : ").append(System.getProperty("neis.ai.base-url", "(unset)")).append("\n");
        out.append("  API key                 : via environment variable '").append(System.getProperty("neis.ai.api-key-ref", "MELIOUS_API_KEY")).append("' (never stored in config)\n");
        out.append("Preconfigured selectors (US-12.1, neis.selectors.preconfigured): ")
                .append(preconfiguredSelectors.preconfigured().isEmpty()
                        ? "<none configured>"
                        : preconfiguredSelectors.preconfigured().size() + " entries")
                .append("\n");
        for (es.jllopez.neis.application.PreconfiguredSelectorProperties.Entry entry
                : preconfiguredSelectors.preconfigured()) {
            out.append("    ").append(entry.address() == null || entry.address().isBlank()
                            ? "<any address>" : entry.address())
                    .append(" / ").append(entry.kind()).append(" : ")
                    .append(entry.selector()).append("\n");
        }
        out.append("Preconfigured filters (US-06.6, neis.filters.preconfigured): ")
                .append(preconfiguredFilters.preconfigured().isEmpty()
                        ? "<none configured>"
                        : preconfiguredFilters.preconfigured().size() + " entries")
                .append("\n");
        for (es.jllopez.neis.application.PreconfiguredFilterProperties.Entry entry
                : preconfiguredFilters.preconfigured()) {
            out.append("    ").append(entry.address()).append(" : ")
                    .append(entry.name()).append(" = ").append(entry.value()).append("\n");
        }
        out.append("Schedules (").append(schedules.entries().size()).append("):\n");
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

    /** A count from a typed number, bounded; null when out of range or
     * not a number. */
    private static Integer parseCount(String input, int min, int max) {
        if (input.isEmpty()) {
            return null;
        }
        int value;
        try {
            value = Integer.parseInt(input);
        } catch (NumberFormatException e) {
            return null;
        }
        if (value < min || value > max) {
            return null;
        }
        return value;
    }
}
