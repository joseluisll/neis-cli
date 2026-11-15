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

    // --- US-14.5 dedup identity (issue #83) ---

    @Test
    void sameUrlIsTheSameCanonicalRecordAcrossAddresses() {
        var a = new NewsEventItem("ADDR-1", "news", "Title A", "d",
                "https://example.eu/story", "2026-11-14");
        var b = new NewsEventItem("ADDR-2", "news", "Title B (different wording)", null,
                "https://example.eu/story", "2026-11-01");
        assertEquals(a.computedCanonicalId(), b.computedCanonicalId(),
                "the stable identifier (URL) groups the rows");
    }

    @Test
    void urlFragmentsAndTrailingSlashesDoNotSplitTheRecord() {
        var plain = new NewsEventItem("ADDR-1", "news", "T", "d",
                "https://example.eu/story", "2026-11-14");
        var fragment = new NewsEventItem("ADDR-1", "news", "T", "d",
                "https://example.eu/story#section", "2026-11-14");
        var slash = new NewsEventItem("ADDR-1", "news", "T", "d",
                "https://example.eu/story/", "2026-11-14");
        assertEquals(plain.computedCanonicalId(), fragment.computedCanonicalId());
        assertEquals(plain.computedCanonicalId(), slash.computedCanonicalId());
    }

    @Test
    void linkLessItemsMatchOnTitlePlusDateBaseline() {
        var human = new NewsEventItem("ADDR-1", "event", "Annual Cybersecurity Day",
                null, null, "Dec 3, 2026");
        var iso = new NewsEventItem("ADDR-2", "news", "annual   cybersecurity day",
                "other description", null, "2026-12-03");
        assertEquals(human.computedCanonicalId(), iso.computedCanonicalId(),
                "title+date baseline: case/whitespace-insensitive title, "
                        + "leniently parsed date, kind-agnostic");
    }

    @Test
    void differentTitlesOrDatesAreDifferentRecords() {
        var base = new NewsEventItem("ADDR-1", "news", "Story", "d",
                null, "2026-11-14");
        var otherTitle = new NewsEventItem("ADDR-2", "news", "Another story", "d",
                null, "2026-11-14");
        var otherDate = new NewsEventItem("ADDR-2", "news", "Story", "d",
                null, "2026-11-15");
        var undated = new NewsEventItem("ADDR-2", "news", "Story", "d",
                null, null);
        assertTrue(!base.computedCanonicalId().equals(otherTitle.computedCanonicalId()));
        assertTrue(!base.computedCanonicalId().equals(otherDate.computedCanonicalId()));
        assertTrue(!base.computedCanonicalId().equals(undated.computedCanonicalId()),
                "an undated item does not match a dated one");
    }

    @Test
    void canonicalIdIsStableAcrossRecomputation() {
        var item = new NewsEventItem("ADDR-1", "news", "Story", "d",
                "https://example.eu/s", "2026-11-14");
        assertEquals(item.computedCanonicalId(), item.computedCanonicalId());
        assertTrue(item.computedCanonicalId().startsWith("cn-"));
    }

    @Test
    void storedCanonicalIdIsTheComponentAccessorNotARecomputation() {
        // US-14.5 review: the record's canonicalId() component is the
        // STORED value (what the store wrote); computedCanonicalId()
        // is the recomputation the stores use on save. Two distinct
        // accessors, so a future backfill cannot silently disagree
        // with reads.
        var stored = new NewsEventItem("ADDR-1", "news", "Story", "d",
                "https://example.eu/s", "2026-11-14",
                null, null, null, null, false, null, "cn-storedvalue");
        assertEquals("cn-storedvalue", stored.canonicalId());
        assertTrue(!stored.canonicalId()
                .equals(stored.computedCanonicalId()));
    }
}
