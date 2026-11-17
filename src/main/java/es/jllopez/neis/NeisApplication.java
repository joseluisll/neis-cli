package es.jllopez.neis;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * NEIS - News and Events Information System.
 *
 * <p>CLI-oriented application built with Spring Boot and Spring Shell,
 * organized as a hexagonal architecture: {@code domain} defines the ports,
 * {@code application} holds the use cases, and {@code infrastructure}
 * provides the connectors (adapters) that implement the ports.</p>
 *
 * <p>Spring Shell 4 note: command classes are plain {@code @Component}
 * beans with {@code @Command}-annotated methods — Spring Boot
 * auto-configuration registers them (do NOT add {@code @EnableCommand}
 * here: it makes {@code SpringShellAutoConfiguration} back off).</p>
 */
@SpringBootApplication
public class NeisApplication {

    public static void main(String[] args) {
        // US-15.5 (issue #105): a command line with an UNBALANCED double
        // quote dies inside Spring Shell's inline parser with a raw
        // CommandNotFoundException — before any command code runs, with
        // no hint that the quote is the cause (verified live for both
        // the single-argument form and the multi-argument form). The
        // parser belongs to the framework; the fix is message-level
        // guidance in OUR entry point: refuse the command here, naming
        // the quoting problem, instead of booting into a stack trace.
        // The invariant checked is the parser's OWN: Spring Shell 4
        // JOINS the argv elements into one line (String.join(" ", args))
        // and its tokenizer enforces quote parity over THAT joined line
        // — a quoted value split across argv elements (shell IFS
        // splitting) is therefore legal and must not be refused.
        String commandLine = String.join(" ", args);
        if (!isQuoteBalanced(commandLine)) {
            System.err.println("Unbalanced double quote in the command: " + commandLine);
            System.err.println("The command parser needs every double quote on the "
                    + "command line paired; a literal quote inside a quoted value is "
                    + "written with a backslash (\\\"). Nothing ran.");
            System.exit(1);
        }
        SpringApplication.run(NeisApplication.class, args);
    }

    /**
     * Whether the text's double quotes are BALANCED — the invariant the
     * command parser's tokenizer enforces over the joined command line
     * (Spring Shell 4 splits it with a quote-parity regex; it has NO
     * doubled-quote escape — a literal quote inside a fully-quoted
     * token is written with a backslash). An even count of quotes
     * passes through to the parser (documented US-15.5 behavior); an
     * odd count — the case the parser crashes on — is refused.
     */
    static boolean isQuoteBalanced(String text) {
        boolean inQuote = false;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '"') {
                inQuote = !inQuote;
            }
        }
        return !inQuote;
    }
}
