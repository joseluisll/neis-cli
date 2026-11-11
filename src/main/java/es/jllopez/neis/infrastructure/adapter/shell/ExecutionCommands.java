package es.jllopez.neis.infrastructure.adapter.shell;

import es.jllopez.neis.application.ExecutionService;
import es.jllopez.neis.domain.model.ExecutionStatus;
import es.jllopez.neis.domain.model.TaskExecution;
import es.jllopez.neis.domain.model.TriggerType;
import org.springframework.shell.core.command.annotation.Command;
import org.springframework.shell.core.command.annotation.CommandGroup;
import org.springframework.shell.core.command.annotation.Option;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Driving adapter: Spring Shell commands triggering task and chain
 * executions on demand (US-05.4, issue #61) and reading the execution
 * records (US-05.3). Manual runs go through the same asynchronous
 * executor as scheduled ones and leave execution records like them.
 */
@Component
@CommandGroup(name = "Executions", description = "Task and chain execution commands")
public class ExecutionCommands {

    private static final DateTimeFormatter TS = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private final ExecutionService executions;

    public ExecutionCommands(ExecutionService executions) {
        this.executions = executions;
    }

    @Command(name = {"execution", "run"}, group = "Executions",
            description = "Execute a task type (or the whole CHAIN) over an address, "
                    + "asynchronously; only valid, verified addresses are eligible (US-04.3)")
    public String run(
            @Option(shortName = 't', longName = "task", required = true,
                    description = "Task type code (WEBSCRAPE, SIMPLIFY, ANALYZE, "
                            + "FILTER, PRINT, CHAIN)") String task,
            @Option(shortName = 'i', longName = "id", required = true,
                    description = "Address id") String id,
            @Option(longName = "wait", defaultValue = "false",
                    description = "Block until the run finishes and show its outcome") boolean wait) {
        TaskExecution submitted = executions.submit(task, id, TriggerType.MANUAL);
        StringBuilder out = new StringBuilder("Submitted ").append(submitted.id())
                .append(": ").append(task).append(" over ").append(id)
                .append(" (running asynchronously)\n");
        if (wait) {
            TaskExecution finished = executions.await(submitted.id());
            out.append(finished == null
                    ? "  still running after the wait timeout — check "
                    + "'execution show --id " + submitted.id() + "'"
                    : render(finished));
        } else {
            out.append("  check it with: execution show --id ").append(submitted.id());
        }
        return out.toString();
    }

    @Command(name = {"execution", "list"}, group = "Executions",
            description = "List the execution records, newest first")
    public String list(
            @Option(longName = "limit", defaultValue = "20",
                    description = "Maximum number of records to show") int limit) {
        int effective = Math.max(0, limit); // a negative limit would throw on stream().limit
        List<TaskExecution> all = executions.list();
        StringBuilder out = new StringBuilder("Executions (")
                .append(Math.min(effective, all.size())).append(" of ")
                .append(all.size()).append("):\n");
        all.stream().limit(effective).forEach(e -> out.append("  ").append(renderLine(e)));
        if (all.isEmpty()) {
            out.append("  <no executions yet — run one with 'execution run'>\n");
        }
        return out.toString();
    }

    @Command(name = {"execution", "show"}, group = "Executions",
            description = "Show one execution record with its outcome")
    public String show(
            @Option(shortName = 'i', longName = "id", required = true,
                    description = "Execution id") String id) {
        return render(executions.show(id));
    }

    @Command(name = {"execution", "types"}, group = "Executions",
            description = "List the task types the executor and scheduler accept")
    public String types() {
        return "Task types: " + String.join(", ", executions.taskTypes());
    }

    private String render(TaskExecution e) {
        StringBuilder out = new StringBuilder();
        out.append(e.id()).append("  ").append(e.taskCode())
                .append(" over ").append(e.addressId() == null ? "<none>" : e.addressId())
                .append("  [").append(e.triggerType()).append("]\n");
        if (e.chainId() != null) {
            out.append("  chain  : ").append(e.chainId()).append("\n");
        }
        out.append("  status  : ").append(e.status()).append("\n");
        out.append("  started : ").append(e.startedAt() == null ? "-" : TS.format(e.startedAt()))
                .append("\n");
        out.append("  finished: ").append(e.finishedAt() == null ? "-" : TS.format(e.finishedAt()))
                .append("\n");
        out.append("  outcome : ").append(e.outcome() == null ? "-" : e.outcome()).append("\n");
        return out.toString();
    }

    private String renderLine(TaskExecution e) {
        return e.id() + "  " + String.format("%-10s", e.taskCode())
                + "  " + String.format("%-8s", e.addressId() == null ? "-" : e.addressId())
                + "  " + String.format("%-9s", e.triggerType())
                + "  " + String.format("%-7s", e.status())
                + "  " + (e.finishedAt() == null ? "(no finish time)" : TS.format(e.finishedAt()))
                + (e.status() == ExecutionStatus.FAILED && e.outcome() != null
                ? "  " + e.outcome() : "")
                + "\n";
    }
}
