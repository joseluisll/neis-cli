package es.jllopez.neis.domain.model;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The chain's filter spec (owner decision 2026-11-12): parsing from the
 * chain-creation options (inline or JSON file content) and from the
 * FILTER step's stored parameters, plus the lenient coherence matching
 * against an address's filter identification.
 */
class FilterSpecTest {

    @Test
    void parsesAnInlineSpec() {
        FilterSpec spec = FilterSpec.parseInline("field=Programme;value=EU4Health");
        assertEquals("Programme", spec.field());
        assertEquals("EU4Health", spec.value());
    }

    @Test
    void inlineParsingIsStrictAboutShape() {
        assertThrows(IllegalArgumentException.class, () -> FilterSpec.parseInline(null));
        assertThrows(IllegalArgumentException.class, () -> FilterSpec.parseInline(""));
        assertThrows(IllegalArgumentException.class,
                () -> FilterSpec.parseInline("Programme;EU4Health"));
        assertThrows(IllegalArgumentException.class,
                () -> FilterSpec.parseInline("field=Programme"));
        assertThrows(IllegalArgumentException.class,
                () -> FilterSpec.parseInline("field=Programme;value=EU4Health;extra=1"));
    }

    @Test
    void parsesASingleCriterionJson() {
        FilterSpec spec = FilterSpec.fromJson(
                "{\"field\": \"Programme\", \"value\": \"EU4Health\", \"note\": \"x\"}");
        assertEquals("Programme", spec.field());
        assertEquals("EU4Health", spec.value());
    }

    @Test
    void parsesTheFirstCriterionOfAFilterList() {
        FilterSpec spec = FilterSpec.fromJson("""
                {"filters": [
                  {"field": "Programme", "value": "EU4Health"},
                  {"field": "Status", "value": "Past"}
                ]}
                """);
        assertEquals("Programme", spec.field());
        assertEquals("EU4Health", spec.value());
    }

    @Test
    void jsonParsingRefusesADocumentWithoutACriterion() {
        assertThrows(IllegalArgumentException.class, () -> FilterSpec.fromJson("{}"));
        assertThrows(IllegalArgumentException.class, () -> FilterSpec.fromJson(null));
        assertThrows(IllegalArgumentException.class,
                () -> FilterSpec.fromJson("{\"filters\": []}"));
    }

    @Test
    void stepParametersCarryTheSpec() {
        FilterSpec spec = FilterSpec.of(Map.of("field", "Programme", "value", "EU4Health"));
        assertEquals("Programme", spec.field());
        assertNull(FilterSpec.of(Map.of()));
        assertThrows(IllegalStateException.class,
                () -> FilterSpec.of(Map.of("field", "Programme")));
    }

    @Test
    void matchingAgainstTheFilterIdentificationIsLenient() {
        FilterSpec spec = new FilterSpec("Event status", "Past");
        assertTrue(spec.matches("Event status"));
        assertTrue(spec.matches("event status = past"));
        assertTrue(spec.matches("facets[status]=past"));
        assertFalse(spec.matches("Programme"));
        assertFalse(spec.matches(null));
    }
}
