package es.jllopez.neis.infrastructure.adapter.shell;

import es.jllopez.neis.application.AddressCatalogService;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.terminal.Terminal;
import org.springframework.shell.core.command.annotation.Command;
import org.springframework.shell.core.command.annotation.CommandGroup;
import org.springframework.stereotype.Component;

/**
 * Driving adapter: the {@code address tui} command (US-09.1, issue #37)
 * opens the interactive address-management screen — list, create, edit
 * and remove addresses, with the verification status and the classified
 * webpage type always shown from live state. The screen runs on the
 * shell's own JLine terminal (no new dependency), so it works both
 * interactively and over piped input (scripted verification: the loop
 * exits cleanly on EOF).
 */
@Component
@CommandGroup(name = "Addresses", description = "Address catalog commands")
public class AddressTuiCommands {

    private final AddressCatalogService catalog;
    private final Terminal terminal;

    public AddressTuiCommands(AddressCatalogService catalog, Terminal terminal) {
        this.catalog = catalog;
        this.terminal = terminal;
    }

    @Command(name = {"address", "tui"}, group = "Addresses",
            description = "Open the interactive address-management screen "
                    + "(list, add, edit, remove)")
    public void tui() {
        LineReader reader = LineReaderBuilder.builder()
                .terminal(terminal)
                .build();
        new AddressTui(catalog, new JLineConsoleIO(reader)).run();
    }

    /** JLine-backed console: prompted reads, flushed writes. */
    static final class JLineConsoleIO implements ConsoleIO {

        private final LineReader reader;

        JLineConsoleIO(LineReader reader) {
            this.reader = reader;
        }

        @Override
        public String readLine(String prompt) {
            return reader.readLine(prompt);
        }

        @Override
        public void println(String line) {
            terminalWriter().println(line);
            terminalWriter().flush();
        }

        private java.io.PrintWriter terminalWriter() {
            return reader.getTerminal().writer();
        }
    }
}
