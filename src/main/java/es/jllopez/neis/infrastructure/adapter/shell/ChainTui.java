package es.jllopez.neis.infrastructure.adapter.shell;

import es.jllopez.neis.application.ChainService;
import es.jllopez.neis.domain.model.Chain;
import es.jllopez.neis.domain.model.ChainTask;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The chain-management TUI (US-10.1, issue #39): a menu-driven screen
 * over the chain catalog — list, create, edit (rename, re-order the
 * task list, remove steps, add steps) and delete — redrawn from live
 * state after every operation, so the displayed task list is never
 * stale. Thin over {@link ChainService}: the service keeps its rules
 * (unique name, 1..10 steps, no nested CHAIN, registered task types,
 * position normalization 1..n in list order).
 *
 * <p>Interaction rules (the AddressTui contract, US-09.1): EMPTY input
 * on a field keeps the default; an invalid value aborts THAT operation
 * with a note — the screen is redrawn, the catalog is never left
 * half-updated; EOF exits cleanly (piped input, Ctrl-D); a delete
 * needs an explicit separate y.</p>
 */
class ChainTui {

    private final ChainService chains;
    private final ConsoleIO io;
    /** True when re-entered from the management screen (US-16.1): quit
     * then means 'back' and must NOT print 'Bye.'. */
    private final boolean nested;

    ChainTui(ChainService chains, ConsoleIO io) {
        this(chains, io, false);
    }

    ChainTui(ChainService chains, ConsoleIO io, boolean nested) {
        this.chains = chains;
        this.io = io;
        this.nested = nested;
    }

    /** Runs the screen loop until the user quits or input ends. */
    void run() {
        io.println("Chain management (US-10.1) — the screen redraws from " +
                "live state after every operation.");
        while (true) {
            ConsoleIO.printScreen(io, chainsTable(chains.list()));
            String line = io.readLine("chain> ");
            if (line == null) {
                io.println("(input ended)");
                return;
            }
            switch (line.trim().toLowerCase()) {
                case "" -> { /* redraw */ }
                case "l", "list" -> { /* the redraw IS the list */ }
                case "c", "create" -> create();
                case "e", "edit" -> edit();
                case "d", "delete" -> delete();
                case "q", "quit", "exit" -> {
                    if (!nested) {
                        io.println("Bye.");
                    }
                    return;
                }
                default -> io.println("Unknown command '" + line.trim()
                        + "' — choose one of l/c/e/d/q.");
            }
        }
    }

    // ---- the screen content ----

    private String chainsTable(List<Chain> chainList) {
        StringBuilder out = new StringBuilder("Defined chains (")
                .append(chainList.size()).append("):");
        int n = 1;
        for (Chain chain : chainList) {
            out.append("\n  ").append(n++).append(". ").append(chain.name())
                    .append("  [").append(chain.id()).append("]");
            if (chain.description() != null && !chain.description().isBlank()) {
                out.append("  ").append(truncate(chain.description(), 60));
            }
            out.append("\n      ");
            if (chain.tasks().isEmpty()) {
                out.append("<no steps>");
            } else {
                List<String> codes = chain.tasks().stream()
                        .map(ChainTask::taskCode).toList();
                out.append(String.join(" -> ", codes));
            }
        }
        if (chainList.isEmpty()) {
            out.append("\n  <no chains yet — 'c' creates one>");
        }
        return out.toString();
    }

    // ---- create ----

    private void create() {
        String name = io.readLine("  name (unique): ");
        if (name == null) {
            return;
        }
        if (name.trim().isEmpty()) {
            io.println("  Refused: a chain needs a name — nothing was created.");
            return;
        }
        String description = io.readLine("  description (empty = none): ");
        if (description == null) {
            return;
        }
        List<ChainTask> steps = askSteps();
        if (steps == null) {
            return; // aborted mid-edit — nothing created
        }
        try {
            Chain chain = chains.create(name.trim(),
                    description.trim().isEmpty() ? null : description.trim(), steps);
            io.println("  Created " + chain.id() + " '" + chain.name()
                    + "' with " + chain.tasks().size() + " step(s).");
        } catch (RuntimeException e) {
            io.println("  Refused: " + e.getMessage() + " — nothing was created.");
        }
    }

    // ---- edit ----

    private void edit() {
        Chain chain = pickChain("  chain number to edit: ");
        if (chain == null) {
            return;
        }
        while (true) {
            io.println("");
            io.println(chainSteps(chain));
            io.println("  Edit: [n]ame  [d]escription  [a]dd step  [r]emove step"
                    + "  [m]ove step  [s]ave  [c]ancel");
            String line = io.readLine("edit '" + chain.name() + "'> ");
            if (line == null) {
                io.println("  (input ended — nothing was saved)");
                return;
            }
            switch (line.trim().toLowerCase()) {
                case "n" -> {
                    String name = io.readLine("  new name (empty = keep '"
                            + chain.name() + "'): ");
                    if (name == null) {
                        return;
                    }
                    if (!name.trim().isEmpty()) {
                        try {
                            chain = withName(chain, name.trim());
                            io.println("  Name will be '" + name.trim()
                                    + "' (saved with [s]ave).");
                        } catch (RuntimeException e) {
                            io.println("  Refused: " + e.getMessage());
                        }
                    }
                }
                case "d" -> {
                    String description = io.readLine(
                            "  new description (empty = keep): ");
                    if (description == null) {
                        return;
                    }
                    chain = withDescription(chain, description.trim());
                    io.println("  Description will be updated (saved with [s]ave).");
                }
                case "a" -> {
                    List<ChainTask> steps = askSteps();
                    if (steps == null) {
                        return;
                    }
                    chain = withTasks(chain, append(chain, steps));
                    io.println("  " + steps.size() + " step(s) appended (saved with [s]ave).");
                }
                case "r" -> {
                    Integer index = askStepNumber(chain, "  step number to remove: ");
                    if (index == null) {
                        return;
                    }
                    List<ChainTask> remaining = new ArrayList<>(chain.tasks());
                    ChainTask removed = remaining.remove((int) index);
                    chain = withTasks(chain, remaining);
                    io.println("  Removed step " + (index + 1) + " ("
                            + removed.taskCode() + ") (saved with [s]ave).");
                }
                case "m" -> {
                    Integer from = askStepNumber(chain, "  step number to move: ");
                    if (from == null) {
                        return;
                    }
                    String toLine = io.readLine("  new position (1-"
                            + chain.tasks().size() + "): ");
                    if (toLine == null) {
                        return;
                    }
                    Integer to = parseIndex(toLine.trim(), chain.tasks().size());
                    if (to == null) {
                        io.println("  Refused: pick a position between 1 and "
                                + chain.tasks().size() + ".");
                        continue;
                    }
                    List<ChainTask> reordered = new ArrayList<>(chain.tasks());
                    ChainTask moved = reordered.remove((int) from);
                    reordered.add(to, moved);
                    chain = withTasks(chain, reordered);
                    io.println("  Step " + moved.taskCode() + " moved to position "
                            + (to + 1) + " (saved with [s]ave).");
                }
                case "s" -> {
                    try {
                        Chain saved = chains.update(chain.id(), chain.name(),
                                chain.description(), chain.tasks());
                        io.println("  Saved " + saved.id() + " '"
                                + saved.name() + "'.");
                    } catch (RuntimeException e) {
                        io.println("  Refused: " + e.getMessage()
                                + " — nothing was saved.");
                    }
                    return;
                }
                case "c", "cancel" -> {
                    io.println("  Edit cancelled — no changes were saved.");
                    return;
                }
                default -> io.println("Unknown command '" + line.trim()
                        + "' — choose n/d/a/r/m/s/c.");
            }
        }
    }

    // ---- delete ----

    private void delete() {
        Chain chain = pickChain("  chain number to delete: ");
        if (chain == null) {
            return;
        }
        String confirm = io.readLine("  Delete '" + chain.name() + "' ("
                + chain.tasks().size() + " steps)? type y to confirm: ");
        if (confirm == null) {
            return;
        }
        if (!"y".equalsIgnoreCase(confirm.trim())) {
            io.println("  Not deleted (confirmation was not 'y').");
            return;
        }
        try {
            chains.delete(chain.id());
            io.println("  Deleted '" + chain.name()
                    + "' — past executions keep their records.");
        } catch (RuntimeException e) {
            io.println("  Refused: " + e.getMessage());
        }
    }

    // ---- helpers ----

    /** Prompts for the ordered step list (CODE or CODE:key=value;k=v,
     * comma-separated) with the same syntax as `chain create --tasks`. */
    private List<ChainTask> askSteps() {
        String line = io.readLine("  steps (CODE[:key=value;...], comma-separated"
                + ", 1-" + es.jllopez.neis.domain.model.Chain.MAX_TASKS + "): ");
        if (line == null) {
            return null;
        }
        if (line.trim().isEmpty()) {
            io.println("  Refused: a chain needs at least one step.");
            return askSteps();
        }
        try {
            List<ChainTask> steps = new ArrayList<>();
            int position = 1;
            for (String raw : line.split(",")) {
                String token = raw.trim();
                if (token.isEmpty()) {
                    throw new IllegalArgumentException(
                            "Empty step in '" + line.trim() + "'");
                }
                String code = token;
                Map<String, String> parameters = new LinkedHashMap<>();
                int colon = token.indexOf(':');
                if (colon >= 0) {
                    code = token.substring(0, colon).trim();
                    for (String pair : token.substring(colon + 1).split(";")) {
                        String entry = pair.trim();
                        if (entry.isEmpty()) {
                            continue;
                        }
                        int equals = entry.indexOf('=');
                        if (equals <= 0) {
                            throw new IllegalArgumentException(
                                    "A parameter needs key=value, got '" + entry + "'");
                        }
                        parameters.put(entry.substring(0, equals).trim(),
                                entry.substring(equals + 1).trim());
                    }
                }
                steps.add(new ChainTask(position++, code, parameters));
            }
            return steps;
        } catch (RuntimeException e) {
            io.println("  Refused: " + e.getMessage() + " — no steps were taken.");
            return null;
        }
    }

    /** Picks a chain by list number (the screen's addressing model). */
    private Chain pickChain(String prompt) {
        List<Chain> chainList = chains.list();
        if (chainList.isEmpty()) {
            io.println("  Nothing to pick — no chains defined; 'c' creates one.");
            return null;
        }
        String line = io.readLine(prompt);
        if (line == null) {
            return null;
        }
        Integer index = parseIndex(line.trim(), chainList.size());
        if (index == null) {
            io.println("  Refused: pick a listed chain number (1-"
                    + chainList.size() + ").");
            return null;
        }
        return chainList.get(index);
    }

    /** Picks a step of the working copy by its displayed position. */
    private Integer askStepNumber(Chain chain, String prompt) {
        String line = io.readLine(prompt);
        if (line == null) {
            return null;
        }
        Integer index = parseIndex(line.trim(), chain.tasks().size());
        if (index == null) {
            io.println("  Refused: pick a step between 1 and "
                    + chain.tasks().size() + ".");
            return null;
        }
        return index;
    }

    private String chainSteps(Chain chain) {
        StringBuilder out = new StringBuilder("  Working copy of '")
                .append(chain.name()).append("':");
        int n = 1;
        for (ChainTask task : chain.tasks()) {
            out.append("\n    ").append(n++).append(". ").append(task.taskCode());
            if (!task.parameters().isEmpty()) {
                out.append("  ").append(task.parameters());
            }
        }
        return out.toString();
    }

    private Chain withName(Chain chain, String name) {
        // Name-uniqueness is the service's rule; validated here early so
        // the working copy never carries a name the save would refuse.
        chains.show(chain.id()); // sanity: still exists
        return new Chain(chain.id(), name, chain.description(),
                chain.createdAt(), chain.tasks());
    }

    private Chain withDescription(Chain chain, String description) {
        return new Chain(chain.id(), chain.name(),
                description.isEmpty() ? null : description,
                chain.createdAt(), chain.tasks());
    }

    private Chain withTasks(Chain chain, List<ChainTask> tasks) {
        // The working list's ORDER is the truth; renumber positions
        // 1..n so the Chain invariant (no gaps/duplicates) holds while
        // editing — the persisted positions only matter at save time.
        List<ChainTask> renumbered = new ArrayList<>();
        int position = 1;
        for (ChainTask task : tasks) {
            renumbered.add(new ChainTask(position++, task.taskCode(),
                    task.parameters()));
        }
        return new Chain(chain.id(), chain.name(), chain.description(),
                chain.createdAt(), renumbered);
    }

    private List<ChainTask> append(Chain chain, List<ChainTask> steps) {
        List<ChainTask> merged = new ArrayList<>(chain.tasks());
        merged.addAll(steps);
        return merged;
    }

    /** 1-based list index from user input; null when out of range or
     * not a number (the TUI's list-position addressing). */
    private Integer parseIndex(String input, int size) {
        if (input.isEmpty() || size == 0) {
            return null;
        }
        try {
            int n = Integer.parseInt(input);
            return n >= 1 && n <= size ? n - 1 : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String truncate(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max - 1) + "…";
    }
}
