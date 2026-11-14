package es.jllopez.neis.domain.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The search criteria model (US-14.3): lenient date parsing (ISO and
 * the extractor's human form — stored dates are human-formatted,
 * verified live) and inclusive range semantics.
 */
class NewsEventQueryTest {

    @Test
    void limitFallsBackToTheDefaultWhenNonPositive() {
        assertEquals(20, new NewsEventQuery(null, null, null, null, 0).limit());
        assertEquals(20, new NewsEventQuery(null, null, null, null, -1).limit());
        assertEquals(5, new NewsEventQuery(null, null, null, null, 5).limit());
    }

    @Test
    void lenientParserAcceptsIsoAndTheExtractorHumanForm() {
        assertEquals("2026-07-16", NewsEventQuery.parseLenient("2026-07-16").toString());
        assertEquals("2026-07-16", NewsEventQuery.parseLenient("Jul 16, 2026").toString());
        // Single-digit day (seen live: 'May 7, 2025').
        assertEquals("2025-05-07", NewsEventQuery.parseLenient("May 7, 2025").toString());
        assertNull(NewsEventQuery.parseLenient("not a date"));
        assertNull(NewsEventQuery.parseLenient("2026-11-5"));
        assertNull(NewsEventQuery.parseLenient(null));
        assertNull(NewsEventQuery.parseLenient("  "));
    }

    @Test
    void rangeBoundsAreInclusiveAndUndatedRowsAreExcluded() {
        NewsEventQuery window = new NewsEventQuery(null, null, "2026-07-01", "2026-07-31", 20);
        assertTrue(window.dateInRange("Jul 1, 2026"), "the from bound is inclusive");
        assertTrue(window.dateInRange("Jul 31, 2026"), "the to bound is inclusive");
        assertTrue(window.dateInRange("Jul 16, 2026"));
        assertFalse(window.dateInRange("Aug 16, 2026"));
        assertFalse(window.dateInRange(null), "undated rows are outside any range");
        assertFalse(window.dateInRange("whenever"), "unparseable rows too");
    }

    @Test
    void noBoundsMeansEverythingAndABadBoundIsInactive() {
        NewsEventQuery unfiltered = new NewsEventQuery(null, null, null, null, 20);
        assertTrue(unfiltered.dateInRange(null),
                "no active range: even undated rows pass");
        // A bound that does not parse is inactive (the driving adapter
        // validates user input, so this only happens for internal use).
        NewsEventQuery badFrom = new NewsEventQuery(null, null, "nope", null, 20);
        assertTrue(badFrom.dateInRange("Jul 16, 2026"));
        NewsEventQuery badTo = new NewsEventQuery(null, null, null, "nope", 20);
        assertTrue(badTo.dateInRange("Jul 16, 2026"));
    }
}
