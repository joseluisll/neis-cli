package es.jllopez.neis;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The entry point's quote-balance guard (US-15.5, issue #105): an
 * unbalanced double quote anywhere in the command never reaches the
 * Spring Shell parser (which dies with a raw
 * CommandNotFoundException) — the entry point refuses it with an error
 * naming the quoting problem. Balanced quotes pass through.
 */
class NeisApplicationTest {

    @Test
    void balancedQuotesPassThrough() {
        assertTrue(NeisApplication.isQuoteBalanced(
                "news search --query \"identity wallet\""));
        // A quoted term with embedded quotes (even parity) is balanced
        // under the parser's tokenizer.
        assertTrue(NeisApplication.isQuoteBalanced(
                "news search --query \"foo\"\"bar\""));
        // No quotes at all.
        assertTrue(NeisApplication.isQuoteBalanced(
                "execution run --task WEBSCRAPE --id ADDR-001"));
    }

    @Test
    void theParserInvariantIsJoinedLineParityNotPerElement() {
        // US-15.5 re-review blocking finding: Spring Shell JOINS the
        // argv elements into one line and enforces quote parity over
        // THAT line. A quoted value IFS-split across elements ('"identity'
        // + 'wallet"') has two odd-parity elements that are jointly even
        // — the parser runs it, so the guard must too.
        String[] splitValue = {"news", "search", "--query", "\"identity", "wallet\""};
        assertTrue(NeisApplication.isQuoteBalanced(String.join(" ", splitValue)),
                "a quoted value split across argv elements is legal");
        // Per-element parity was the OLD (wrong) guard: it would refuse
        // this invocation. The joined-line check does not.
        assertFalse(NeisApplication.isQuoteBalanced(splitValue[3]));
        assertFalse(NeisApplication.isQuoteBalanced(splitValue[4]));
    }

    @Test
    void anUnbalancedQuoteIsRefused() {
        // The reproduced failure: a lone quote inside a value.
        assertFalse(NeisApplication.isQuoteBalanced(
                "news search --query identity\"wallet"));
        // An opening quote that is never closed.
        assertFalse(NeisApplication.isQuoteBalanced(
                "news search --query \"wallet"));
        // A closing quote with no opening one.
        assertFalse(NeisApplication.isQuoteBalanced("wallet\""));
    }
}
