package es.jllopez.neis.infrastructure.adapter.shell;

import es.jllopez.neis.application.ChainService;
import es.jllopez.neis.application.ExecutionService;
import es.jllopez.neis.domain.model.Chain;
import es.jllopez.neis.domain.model.ChainTask;
import es.jllopez.neis.domain.model.FilterSpec;
import es.jllopez.neis.domain.model.TaskExecution;
import es.jllopez.neis.domain.model.TriggerType;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.terminal.Terminal;
import org.springframework.shell.core.command.annotation.Command;
import org.springframework.shell.core.command.annotation.CommandGroup;
import org.springframework.shell.core.command.annotation.Option;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Driving adapter: Spring Shell commands managing chain definitions
 * (US-08.1, issue #33) and running them over an address (US-08.2,
 * issue #34). A chain run goes through the same asynchronous executor
 * and leaves execution records like any task run.
 */
@Component
@CommandGroup(name = "Chains", description = "Chain of tasks management commands")
public class ChainCommands {

    private static final DateTimeFormatter TS = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private final ChainService chains;
    private final ExecutionService executions;
    private final Terminal terminal;

    public ChainCommands(ChainService chains, ExecutionService executions,
                         Terminal terminal) {
        this.chains = chains;
        this.executions = executions;
        this.terminal = terminal;
    }

    @Command(name = {"chain", "create"}, group = "Chains",
            description = "Create a chain: an ordered list of 1..10 tasks (US-08.1). "
                    + "Steps syntax: CODE or CODE:key=value;key2=value2, steps separated by ','")
    public String create(
            @Option(shortName = 'n', longName = "name", required = true,
                    description = "Unique chain name") String name,
            @Option(shortName = 'd', longName = "description",
                    description = "What the chain is for") String description,
            @Option(shortName = 't', longName = "tasks", required = true,
                    description = "Ordered steps, e.g. \"WEBSCRAPE,PRINT:max-items=10\". "
                            + "PRINT/CHAIN steps accept max-items and max-pages")
            String tasks,
            @Option(shortName = 'f', longName = "filter",
                    description = "Filter criterion the chain applies (owner decision "
                            + "2026-11-12): 'field=<name>;value=<value>'. The chain's "
                            + "FILTER step applies it offline against the address's "
                            + "stored filter catalogue; requires a FILTER step")
            String filter,
            @Option(longName = "filter-spec",
                    description = "Path to a JSON filter spec file "
                            + "({\"field\":..., \"value\":...} or {\"filters\":[...]}); "
                            + "same semantics as --filter, resolved at creation")
            String filterSpecFile) {
        FilterSpec spec = resolveFilterSpec(filter, filterSpecFile);
        List<ChainTask> steps = parseSteps(tasks);
        if (spec != null) {
            steps = withFilterSpec(steps, spec);
        }
        Chain chain = chains.create(name, description, steps);
        StringBuilder out = new StringBuilder("Created ").append(chain.id())
                .append(" '").append(chain.name()).append("': ")
                .append(chain.tasks().size()).append(" steps\n");
        for (ChainTask task : chain.tasks()) {
            out.append("  ").append(task.position()).append(". ").append(task.taskCode());
            if (!task.parameters().isEmpty()) {
                out.append(" ").append(task.parameters());
            }
            out.append("\n");
        }
        if (spec != null) {
            out.append("  filter: ").append(spec.field()).append(" = ")
                    .append(spec.value()).append(" (applied offline by the FILTER step)\n");
        }
        out.append("Run it with: chain run --id ").append(chain.id())
                .append(" --address <address-id>");
        return out.toString();
    }

    @Command(name = {"chain", "tui"}, group = "Chains",
            description = "Open the interactive chain-management screen "
                    + "(list, create, edit, reorder steps, delete)")
    public void tui() {
        LineReader reader = LineReaderBuilder.builder()
                .terminal(terminal)
                .build();
        new ChainTui(chains, executions, new AddressTuiCommands.JLineConsoleIO(reader)).run();
    }

    @Command(name = {"chain", "update"}, group = "Chains",
            description = "Replace a chain's definition: name, description and the "
                    + "ordered task list (the list IS the order). Empty options keep "
                    + "the current values")
    public String update(
            @Option(shortName = 'i', longName = "id", required = true,
                    description = "The chain id to update") String id,
            @Option(shortName = 'n', longName = "name",
                    description = "New unique name (empty = keep)") String name,
            @Option(shortName = 'd', longName = "description",
                    description = "New description (empty = keep)") String description,
            @Option(shortName = 't', longName = "tasks",
                    description = "New ordered steps, e.g. \"WEBSCRAPE,SIMPLIFY,"
                            + "PRINT:max-items=10\" (empty = keep)") String tasks) {
        Chain current = chains.show(id);
        List<ChainTask> steps = tasks == null || tasks.isBlank()
                ? current.tasks()
                : parseSteps(tasks);
        Chain updated = chains.update(id,
                name == null || name.isBlank() ? null : name,
                description, steps);
        return "Updated " + updated.id() + " '" + updated.name() + "': "
                + updated.tasks().size() + " steps";
    }

    @Command(name = {"chain", "delete"}, group = "Chains",
            description = "Delete a chain definition (past executions keep their records)")
    public String delete(
            @Option(shortName = 'i', longName = "id", required = true,
                    description = "The chain id to delete") String id) {
        Chain removed = chains.show(id);
        chains.delete(id);
        return "Deleted " + id + " '" + removed.name()
                + "' — past executions keep their records";
    }

    /** Resolves the --filter / --filter-spec options to one spec (at
     * most one of them may be given). */
    private FilterSpec resolveFilterSpec(String filter, String filterSpecFile) {
        if (filter != null && filterSpecFile != null) {
            throw new IllegalArgumentException(
                    "Give either --filter or --filter-spec, not both");
        }
        if (filter != null) {
            return FilterSpec.parseInline(filter);
        }
        if (filterSpecFile != null) {
            try {
                return FilterSpec.fromJson(
                        java.nio.file.Files.readString(java.nio.file.Path.of(filterSpecFile)));
            } catch (java.io.IOException e) {
                throw new IllegalArgumentException(
                        "Cannot read the filter spec file " + filterSpecFile + ": "
                                + e.getMessage());
            }
        }
        return null;
    }

    /** Attaches the resolved spec to the chain's FILTER step. */
    private List<ChainTask> withFilterSpec(List<ChainTask> steps, FilterSpec spec) {
        boolean hasFilterStep = steps.stream().anyMatch(t -> "FILTER".equals(t.taskCode()));
        if (!hasFilterStep) {
            throw new IllegalArgumentException("A chain with --filter needs a FILTER step");
        }
        List<ChainTask> withSpec = new ArrayList<>();
        for (ChainTask step : steps) {
            if ("FILTER".equals(step.taskCode()) && step.parameters().isEmpty()) {
                withSpec.add(new ChainTask(step.position(), step.taskCode(),
                        Map.of(FilterSpec.FIELD_KEY, spec.field(),
                                FilterSpec.VALUE_KEY, spec.value())));
            } else {
                withSpec.add(step);
            }
        }
        return withSpec;
    }

    @Command(name = {"chain", "list"}, group = "Chains",
            description = "List the chain definitions")
    public String list() {
        List<Chain> all = chains.list();
        StringBuilder out = new StringBuilder("Chains (").append(all.size()).append("):\n");
        for (Chain chain : all) {
            out.append("  ").append(chain.id()).append("  '").append(chain.name())
                    .append("'  ").append(chain.tasks().size()).append(" tasks");
            if (chain.description() != null && !chain.description().isBlank()) {
                out.append("  — ").append(chain.description());
            }
            out.append("\n");
        }
        if (all.isEmpty()) {
            out.append("  <no chains yet — create one with 'chain create'>\n");
        }
        return out.toString();
    }

    @Command(name = {"chain", "show"}, group = "Chains",
            description = "Show one chain definition with its ordered tasks and parameters")
    public String show(
            @Option(shortName = 'i', longName = "id", required = true,
                    description = "Chain id") String id) {
        Chain chain = chains.show(id);
        StringBuilder out = new StringBuilder(chain.id()).append("  '")
                .append(chain.name()).append("'\n");
        out.append("  created: ").append(chain.createdAt() == null
                ? "-" : TS.format(chain.createdAt())).append("\n");
        if (chain.description() != null && !chain.description().isBlank()) {
            out.append("  description: ").append(chain.description()).append("\n");
        }
        for (ChainTask task : chain.tasks()) {
            out.append("  ").append(task.position()).append(". ").append(task.taskCode());
            if (!task.parameters().isEmpty()) {
                out.append(" ").append(task.parameters());
            }
            out.append("\n");
        }
        return out.toString();
    }

    @Command(name = {"chain", "run"}, group = "Chains",
            description = "Execute a chain over an address, asynchronously; the tasks "
                    + "run sequentially in position order (US-08.2)")
    public String run(
            @Option(shortName = 'i', longName = "id", required = true,
                    description = "Chain id") String id,
            @Option(shortName = 'a', longName = "address", required = true,
                    description = "Address id") String address,
            @Option(longName = "wait", defaultValue = "false",
                    description = "Block until the run finishes and show its outcome")
            boolean wait) {
        TaskExecution submitted = executions.submitChain(id, address, TriggerType.MANUAL);
        StringBuilder out = new StringBuilder("Submitted ").append(submitted.id())
                .append(": chain ").append(id).append(" over ").append(address)
                .append(" (running asynchronously)\n");
        if (wait) {
            TaskExecution finished = executions.await(submitted.id());
            out.append(finished == null
                    ? "  still running after the wait timeout — check "
                    + "'execution show --id " + submitted.id() + "'"
                    : "  status : " + finished.status() + "\n  outcome: "
                    + (finished.outcome() == null ? "-" : finished.outcome()));
        } else {
            out.append("  check it with: execution show --id ").append(submitted.id());
        }
        return out.toString();
    }

    /**
     * Parses the ordered step list: steps separated by ','; a step is
     * CODE or CODE:key=value;key2=value2. Positions come from the
     * order (1..n), so the option value IS the chain order.
     */
    private List<ChainTask> parseSteps(String tasks) {
        List<ChainTask> steps = new ArrayList<>();
        int position = 1;
        for (String raw : tasks.split(",")) {
            String token = raw.trim();
            if (token.isEmpty()) {
                throw new IllegalArgumentException(
                        "Empty chain step in: '" + tasks + "'");
            }
            String code = token;
            Map<String, String> parameters = Map.of();
            int colon = token.indexOf(':');
            if (colon >= 0) {
                code = token.substring(0, colon).trim();
                parameters = parseParameters(token.substring(colon + 1));
            }
            steps.add(new ChainTask(position++, code, parameters));
        }
        return steps;
    }

    private Map<String, String> parseParameters(String raw) {
        Map<String, String> parameters = new LinkedHashMap<>();
        for (String pair : raw.split(";")) {
            String entry = pair.trim();
            if (entry.isEmpty()) {
                continue;
            }
            int equals = entry.indexOf('=');
            if (equals <= 0) {
                throw new IllegalArgumentException(
                        "A chain step parameter must be key=value, got: '" + entry + "'");
            }
            parameters.put(entry.substring(0, equals).trim(),
                    entry.substring(equals + 1).trim());
        }
        return parameters;
    }
}
