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
        // US-15.5 (issue #105): a command argument with an UNBALANCED
        // double quote dies inside Spring Shell's inline parser with a
        // raw CommandNotFoundException — before any command code runs,
        // with no hint that the quote is the cause (verified live for
        // both the single-argument form and the multi-argument form).
        // The parser belongs to the framework; the fix is message-level
        // guidance in OUR entry point: refuse the command here, naming
        // the quoting problem, instead of booting into a stack trace.
        for (String arg : args) {
            if (!isQuoteBalanced(arg)) {
                System.err.println("Unbalanced double quote in the command: " + arg);
                System.err.println("The command parser needs every double quote paired; "
                        + "a literal quote inside a value is written as two quotes (\"\"). "
                        + "Nothing ran.");
                System.exit(1);
            }
        }
        SpringApplication.run(NeisApplication.class, args);
    }

    /**
     * Whether the text's double quotes are balanced under the command
     * parser's tokenizer: a quote toggles the quoted state, and a
     * doubled quote ({@code ""}) inside a quoted span is an ESCAPED
     * literal quote, not a toggle. Balanced quotes pass through to the
     * parser (documented US-15.5 behavior); only an unclosed quoted
     * span — the case the parser crashes on — is refused.
     */
    static boolean isQuoteBalanced(String text) {
        boolean inQuote = false;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) != '"') {
                continue;
            }
            if (inQuote && i + 1 < text.length() && text.charAt(i + 1) == '"') {
                i++; // an escaped literal quote: skip the pair
                continue;
            }
            inQuote = !inQuote;
        }
        return !inQuote;
    }
}
