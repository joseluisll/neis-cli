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

    // ---- US-06.19 (issue #150): checkbox facet groups + free text ----

    @Test
    void parsesCheckboxFacetGroupsBySharedIdPrefix() {
        // The generic client-rendered facet-panel shape: checkbox
        // inputs whose ids share a <group>-<value> pattern, named by
        // label-for. No site knowledge — standard HTML5 semantics.
        String html = "<form>"
                + "<input type=\"checkbox\" id=\"checkbox-status-upcoming\" "
                + "  aria-label=\"Input Unchecked\" class=\"eui-input-checkbox\">"
                + "<label for=\"checkbox-status-upcoming\">Upcoming</label>"
                + "<input type=\"checkbox\" id=\"checkbox-status-ongoing\">"
                + "<label for=\"checkbox-status-ongoing\">Ongoing</label>"
                + "<input type=\"checkbox\" id=\"checkbox-status-past\">"
                + "<label for=\"checkbox-status-past\">Past</label>"
                + "<input type=\"checkbox\" id=\"checkbox-programme-horizon\">"
                + "<label for=\"checkbox-programme-horizon\">Horizon Europe</label>"
                + "</form>";
        List<FilterDefinition> filters = detector.parse(html);
        assertEquals(2, filters.size());
        assertEquals("Status", filters.get(0).name());
        assertEquals(List.of("Upcoming", "Ongoing", "Past"), filters.get(0).values());
        assertEquals("Programme", filters.get(1).name());
        assertEquals(List.of("Horizon Europe"), filters.get(1).values());
    }

    @Test
    void radioGroupsGroupByNameAttribute() {
        String html = "<form>"
                + "<input type=\"radio\" name=\"sort\" id=\"sort-asc\">"
                + "<label for=\"sort-asc\">Ascending</label>"
                + "<input type=\"radio\" name=\"sort\" id=\"sort-desc\">"
                + "<label for=\"sort-desc\">Descending</label>"
                + "</form>";
        List<FilterDefinition> filters = detector.parse(html);
        assertEquals(1, filters.size());
        assertEquals("Sort", filters.get(0).name());
        assertEquals(List.of("Ascending", "Descending"), filters.get(0).values());
    }

    @Test
    void labeledTextInputsAreFreeTextDefinitions() {
        String html = "<form>"
                + "<input type=\"search\" id=\"q\" placeholder=\"Quick search\">"
                + "<input type=\"password\" id=\"pw\" aria-label=\"Password\">"
                + "</form>";
        List<FilterDefinition> filters = detector.parse(html);
        assertEquals(1, filters.size());
        assertEquals("Quick search", filters.get(0).name());
        assertTrue(filters.get(0).values().isEmpty(),
                "empty values = free-text per the FilterDefinition convention");
    }

    @Test
    void unlabeledCheckboxesAreSkipped() {
        String html = "<form>"
                + "<input type=\"checkbox\" id=\"cb-anon\">"
                + "<input type=\"checkbox\" id=\"cb-named-x\">"
                + "<label for=\"cb-named-x\">Named</label>"
                + "</form>";
        List<FilterDefinition> filters = detector.parse(html);
        assertEquals(1, filters.size());
        assertEquals(List.of("Named"), filters.get(0).values());
    }

    @Test
    void parseCombinesSelectsCheckboxGroupsAndTextInputs() {
        String html = "<form>"
                + "<select name=\"year\"><option>2025</option><option>2026</option></select>"
                + "<input type=\"checkbox\" id=\"facet-kind-news\">"
                + "<label for=\"facet-kind-news\">News article</label>"
                + "<input type=\"text\" id=\"terms\" aria-label=\"Keywords\">"
                + "</form>";
        List<FilterDefinition> filters = detector.parse(html);
        assertEquals(3, filters.size());
        assertEquals("year", filters.get(0).name());
        assertEquals("Kind", filters.get(1).name());
        assertEquals("Keywords", filters.get(2).name());
    }
}
