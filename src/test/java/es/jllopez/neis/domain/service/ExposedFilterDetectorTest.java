package es.jllopez.neis.domain.service;

import es.jllopez.neis.domain.model.FilterDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Generic exposed-filter heuristics (US-17): page-agnostic HTML5 shapes
 * only — form with select dropdowns — never site-specific knowledge.
 */
class ExposedFilterDetectorTest {

    private final ExposedFilterDetector detector = new ExposedFilterDetector();

    @Test
    void detectsFormWithSelects() {
        String html = "<form class=\"list-facets-form\">"
                + "<select name=\"status[]\"><option>All</option><option>Open</option></select>"
                + "</form>";
        assertTrue(detector.detect(html));
    }

    @Test
    void selectsWithoutFormAreNotFilterUi() {
        String html = "<select name=\"sort\"><option>asc</option></select>";
        assertFalse(detector.detect(html));
    }

    @Test
    void parsesSelectFieldsWithOptions() {
        String html = "<form>"
                + "<select name=\"type[]\" aria-label=\"Event type\">"
                + "<option>Select</option><option>Conference</option><option>Info day</option>"
                + "<option>Conference</option></select>"
                + "<select name=\"year\"><option>2025</option><option>2026</option></select>"
                + "</form>";
        List<FilterDefinition> filters = detector.parseSelects(html);
        assertEquals(2, filters.size());
        assertEquals("Event type", filters.get(0).name());
        assertEquals(List.of("Conference", "Info day"), filters.get(0).values(),
                "placeholder dropped, duplicates removed");
        assertEquals("year", filters.get(1).name());
        assertEquals(List.of("2025", "2026"), filters.get(1).values());
    }

    @Test
    void unnamedSelectFallsBackToPlaceholderName() {
        String html = "<form><select><option>one</option></select></form>";
        List<FilterDefinition> filters = detector.parseSelects(html);
        assertEquals(1, filters.size());
        assertEquals("(unnamed select)", filters.get(0).name());
    }

    @Test
    void blankInputYieldsNothing() {
        assertFalse(detector.detect(null));
        assertTrue(detector.parseSelects(null).isEmpty());
    }
}
