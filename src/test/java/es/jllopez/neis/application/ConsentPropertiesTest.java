package es.jllopez.neis.application;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The consent-dialog policy binding (US-06.13, issue #137): ACCEPT
 * or REJECT, default REJECT, unknown values fail loud (never run an
 * undeclared stance). */
class ConsentPropertiesTest {

    @Test
    void defaultIsRejectAndBothPoliciesBind() {
        assertEquals("REJECT", new ConsentProperties(null).policy(),
                "the conservative default: no consent granted unless "
                        + "the operator says so");
        assertEquals("REJECT", new ConsentProperties("  ").policy());
        assertEquals("ACCEPT", new ConsentProperties("accept").policy(),
                "case- and whitespace-tolerant binding");
        assertTrue(new ConsentProperties("ACCEPT").isAccept());
        assertFalse(new ConsentProperties("REJECT").isAccept());
    }

    @Test
    void unknownPolicyRefusesLoud() {
        // US-06.15 pattern: a typo must never run an undeclared stance.
        assertThrows(IllegalArgumentException.class,
                () -> new ConsentProperties("MAYBE"));
        assertThrows(IllegalArgumentException.class,
                () -> new ConsentProperties("accept-all"));
    }
}
