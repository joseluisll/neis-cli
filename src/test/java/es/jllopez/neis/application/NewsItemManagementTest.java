package es.jllopez.neis.application;

import es.jllopez.neis.domain.model.NewsEventItem;
import es.jllopez.neis.domain.model.NewsItemCorrection;
import es.jllopez.neis.testsupport.InMemoryNewsEventStorePort;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Manual item management (US-14.10, issue #124): traceable
 * corrections (what changed, when, by whom; canonical id and
 * provenance preserved) and pruning of false positives (soft-delete,
 * excluded from every repository view, provenance readable,
 * never resurrected by re-extraction). */
class NewsItemManagementTest {

    private final InMemoryNewsEventStorePort store =
            new InMemoryNewsEventStorePort();

    private NewsEventItem storeOne(String title, String date, String url) {
        NewsEventItem item = new NewsEventItem("ADDR-001", "news", title,
                "A description", url, date);
        store.saveAll("ADDR-001", "news", List.of(item), "EXEC-x", "CONT-x");
        return store.all().get(0);
    }

    @Test
    void correctionChangesFieldsAndRecordsTheTrace() {
        NewsEventItem item = storeOne("Wrong title", "Jul 16, 2026",
                null); // url-less: the correction exercises the date path
        String canonicalId = item.canonicalId();
        String beforeAddress = item.addressId();
        String beforeExecution = item.executionId();
        String beforeContent = item.contentId();

        int rows = store.correct(canonicalId, new NewsItemCorrection(
                "Right title", null, "2026-07-16", null,
                "extractor mis-read the date"), "operator (test)");
        assertEquals(1, rows);

        NewsEventItem after = store.byCanonicalId(canonicalId).orElseThrow();
        assertEquals("Right title", after.title());
        assertEquals("2026-07-16", after.date());
        assertEquals("2026-07-16", after.dateNormalized(),
                "the corrected date is re-normalized (US-14.9)");
        assertEquals(canonicalId, after.canonicalId(),
                "the canonical id is the identity anchor — never "
                        + "recomputed by a correction (US-14.5)");
        assertEquals(beforeAddress, after.addressId());
        assertEquals(beforeExecution, after.executionId());
        assertEquals(beforeContent, after.contentId(),
                "the US-14.2 provenance is never broken");

        List<String> trace = store.correctionTrace(canonicalId);
        assertEquals(1, trace.size(), "one recorded correction");
        String entry = trace.get(0);
        assertTrue(entry.contains("\"by\":\"operator (test)\""),
                "the trace records by whom: " + entry);
        assertTrue(entry.contains("Right title")
                        && entry.contains("Wrong title"),
                "the trace records old -> new: " + entry);
        assertTrue(entry.contains("mis-read"),
                "the trace records the note: " + entry);
    }

    @Test
    void correctionOnTheFreshStoreCantResurrectOrRecomputeTheCanonicalId() {
        NewsEventItem item = storeOne("Keep me", "2026-07-16",
                "https://example.eu/item-1");
        String canonicalId = item.canonicalId();
        // A correction of title on a URL-keyed item: the canonical key
        // derives from the URL, so the id must not move.
        store.correct(canonicalId, new NewsItemCorrection("New title",
                null, null, null, null), "operator");
        NewsEventItem after = store.byCanonicalId(canonicalId).orElseThrow();
        assertEquals(canonicalId, after.canonicalId());
        assertEquals("New title", after.title());
    }

    @Test
    void prunedRecordIsExcludedFromEveryViewButProvenanceStaysReadable() {
        NewsEventItem a = storeOne("False positive", "2026-07-16",
                "https://example.eu/fp");
        NewsEventItem b = storeOne("Real news", "2026-07-17",
                "https://example.eu/real");
        assertEquals(2, store.count());

        int rows = store.prune(a.canonicalId(), "promo card, not news");
        assertEquals(1, rows);

        assertEquals(1, store.count(), "the pruned record leaves the count");
        assertTrue(store.all().stream().noneMatch(i ->
                        i.canonicalId().equals(a.canonicalId())),
                "excluded from all()");
        assertTrue(store.byAddress("ADDR-001").stream().noneMatch(i ->
                        i.canonicalId().equals(a.canonicalId())),
                "excluded from byAddress()");
        assertTrue(store.search(new es.jllopez.neis.domain.model.NewsEventQuery(
                        null, null, null, null, 50)).stream().noneMatch(i ->
                        i.canonicalId().equals(a.canonicalId())),
                "excluded from search()");
        assertTrue(store.search("False positive",
                        new es.jllopez.neis.domain.model.NewsEventQuery(
                                null, null, null, null, 50)).isEmpty(),
                "excluded from full-text search");

        // The rows stay: provenance history remains inspectable
        // (sightings + the item detail still resolve).
        assertTrue(store.byCanonicalId(a.canonicalId()).isPresent(),
                "the soft-deleted record stays inspectable");
        assertFalse(store.sightings(a.canonicalId()).isEmpty(),
                "the sighting history of a pruned record stays readable");
    }

    @Test
    void reExtractionNeverResurrectsAPrunedRecord() {
        NewsEventItem a = storeOne("False positive", "2026-07-16",
                "https://example.eu/fp2");
        store.prune(a.canonicalId(), "not news");
        // The source re-extracts the same story (same url, same key):
        store.saveAll("ADDR-001", "news", List.of(new NewsEventItem(
                "ADDR-001", "news", "False positive", "A description",
                "https://example.eu/fp2", "2026-07-16")), "EXEC-y", "CONT-y");
        assertEquals(0, store.count(),
                "the pruned record stays pruned (the operator's "
                        + "decision stands)");
    }

    @Test
    void unknownAndEmptyCorrectionsAreNoOps() {
        storeOne("Something", "2026-07-16", "https://example.eu/s");
        assertEquals(0, store.correct("cn-unknown",
                new NewsItemCorrection("X", null, null, null, null), "op"));
        assertEquals(0, store.prune("cn-unknown", "reason"));
        assertEquals(0, store.correct("cn-unknown",
                new NewsItemCorrection(null, null, null, null, null), "op"),
                "an empty correction changes nothing");
    }
}
