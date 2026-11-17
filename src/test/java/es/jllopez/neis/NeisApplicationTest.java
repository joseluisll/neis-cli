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
        // A quoted term with an EMBEDDED doubled quote (the FTS escape,
        // US-14.7) is balanced under the tokenizer.
        assertTrue(NeisApplication.isQuoteBalanced(
                "news search --query \"foo\"\"bar\""));
        // No quotes at all.
        assertTrue(NeisApplication.isQuoteBalanced(
                "execution run --task WEBSCRAPE --id ADDR-001"));
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
