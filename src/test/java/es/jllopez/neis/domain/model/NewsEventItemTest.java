package es.jllopez.neis.domain.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The mandatory metadata rule (US-14.4, issue #74): title,
 * description and date are the mandatory minimum; blank counts as
 * missing so extraction fallbacks cannot pass as data. */
class NewsEventItemTest {

    @Test
    void completeItemIsMissingNothing() {
        var item = new NewsEventItem("ADDR-1", "news", "Title",
                "Description", "https://example.eu/n1", "2026-11-14");
        assertTrue(item.missingMandatory().isEmpty());
    }

    @Test
    void missingFieldsAreListedInOrder() {
        var item = new NewsEventItem("ADDR-1", "event", "Title only",
                null, null, null);
        assertEquals(List.of("description", "date"), item.missingMandatory());
    }

    @Test
    void blankFieldsCountAsMissing() {
        var item = new NewsEventItem("ADDR-1", "news", "  ",
                "", "https://example.eu/n1", "   ");
        assertEquals(List.of("title", "description", "date"),
                item.missingMandatory());
    }

    @Test
    void dateIsMandatoryForBothKinds() {
        // Publication date for news, event date for events: the same
        // mandatory `date` field either way.
        assertEquals(List.of("date"),
                new NewsEventItem("ADDR-1", "news", "T", "D",
                        "https://example.eu/n1", null).missingMandatory());
        assertEquals(List.of("date"),
                new NewsEventItem("ADDR-1", "event", "T", "D",
                        "https://example.eu/e1", null).missingMandatory());
    }

    @Test
    void extractedFactoryCarriesOptionalMetadataAndDropsBlanks() {
        var item = NewsEventItem.extracted("ADDR-1", "news", "T", "D",
                "https://example.eu/n1", "2026-11-14",
                "https://example.eu/img.jpg", "  ");
        assertEquals("https://example.eu/img.jpg", item.picture());
        assertEquals(null, item.location());
        assertTrue(item.missingMandatory().isEmpty());
    }
}
