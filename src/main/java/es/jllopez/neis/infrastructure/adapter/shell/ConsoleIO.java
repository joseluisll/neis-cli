package es.jllopez.neis.infrastructure.adapter.shell;

import java.util.List;

/**
 * The text the TUI reads and writes (US-09.1, issue #37), behind this
 * minimal interface: production binds it to the shell's JLine line
 * reader (prompted input, terminal writer); tests script it with a
 * canned input queue. The TUI logic never touches JLine directly.
 */
interface ConsoleIO {

    /**
     * Reads one line with the given prompt; null on EOF (piped input
     * exhausted or Ctrl-D) or on Ctrl-C (JLine throws
     * UserInterruptException) — an operation the prompt belongs to
     * aborts, and at the main menu the screen exits; never spins
     * (US-09.1 review finding: Ctrl-C used to surface uncaught).
     */
    String readLine(String prompt);

    void println(String line);

    /** One bound screen: the table plus the menu, framed by blanks. */
    static void printScreen(ConsoleIO io, String table) {
        io.println("");
        io.println(table);
        io.println("");
        io.println("Commands: [l]ist  [a]dd  [e]dit  [r]emove  [q]uit");
    }

    /** The address table of one screen, newest state every redraw. */
    static String addressTable(List<es.jllopez.neis.domain.model.Address> addresses) {
        StringBuilder out = new StringBuilder("Registered addresses (")
                .append(addresses.size()).append("):");
        for (es.jllopez.neis.domain.model.Address a : addresses) {
            out.append("\n  ").append(a.id())
                    .append("  [").append(a.itemKind()).append("]  ")
                    .append(a.name())
                    .append("\n        ").append(a.url())
                    .append("\n        verification: ")
                    .append(a.verificationStatus())
                    .append(a.verifiedAt() == null ? "" : " (" + a.verifiedAt() + ")")
                    .append("  |  webpage type: ")
                    .append(a.webpageType() == null ? "unclassified" : a.webpageType());
        }
        return out.toString();
    }
}
