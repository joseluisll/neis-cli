package es.jllopez.neis.application;

import es.jllopez.neis.domain.model.BulkPruneSelection;
import es.jllopez.neis.domain.model.NewsEventItem;
import es.jllopez.neis.testsupport.InMemoryContentStorePort;
import es.jllopez.neis.testsupport.InMemoryExecutionStorePort;
import es.jllopez.neis.testsupport.InMemoryNewsEventStorePort;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The bulk prune use case (US-14.13, issue #146): selection-driven
 * soft delete with the listing's query semantics — dry-run never
 * writes, confirm prunes whole canonical groups, zero matches are a
 * no-op success, explicit ids resolve (unknown ones reported), and
 * the refusals (empty selection, blank reason, invalid range bound)
 * are loud.
 */
class NewsBulkPruneTest {

    private final InMemoryNewsEventStorePort store = new InMemoryNewsEventStorePort();
    private final InMemoryExecutionStorePort executions =
            new InMemoryExecutionStorePort(List.of("WEBSCRAPE"));
    private final InMemoryContentStorePort contents = new InMemoryContentStorePort();
    private final NewsEventService service = new NewsEventService(store,
            executions, new ContentStoreService(contents, "target/test-content"));

    private void seed(String addressId, String kind, String title,
                      String url, String date) {
        store.saveAll(addressId, kind, List.of(new NewsEventItem(
                addressId, kind, title, "description of " + title,
                url, date)), "exec-1", "CONT-1");
    }

    @Test
    void dryRunWritesNothing() {
        seed("ADDR-001", "news", "Budget cut", "https://x.eu/1", "Jul 16, 2026");
        seed("ADDR-001", "news", "New call", "https://x.eu/2", "Jul 17, 2026");

        var preview = service.bulkPrunePreview(new BulkPruneSelection(
                "ADDR-001", null, null, null, null, "all", null, null));

        assertEquals(2, preview.matchedRecords());
        // nothing written: both records still live
        assertEquals(2, store.count());
        assertTrue(store.search(new es.jllopez.neis.domain.model.NewsEventQuery(
                null, null, null, null, 20)).size() == 2);
    }

    @Test
    void confirmedBulkPrunesWholeCanonicalGroup() {
        // one logical record on TWO addresses (same URL identity — one
        // canonical id, two rows), per the US-14.5 grouping
        seed("ADDR-001", "news", "Joint story", "https://x.eu/shared", "Jul 16, 2026");
        seed("ADDR-002", "news", "Joint story", "https://x.eu/shared", "Jul 16, 2026");
        seed("ADDR-001", "news", "Unrelated", "https://x.eu/other", "Jul 17, 2026");
        // row-identity pick (the listing is newest-first): select the
        // shared story BY ITS TERMS, never by list position
        String cid = store.search("Joint", new es.jllopez.neis.domain.model.NewsEventQuery(
                "ADDR-001", null, null, null, 20)).get(0).canonicalId();

        var outcome = service.bulkPrune(new BulkPruneSelection(
                null, null, null, null, null, "all",
                List.of(cid), "false positive"));

        assertEquals(1, outcome.prunedRecords());
        // every row of the group is gone — both addresses
        assertEquals(2, outcome.prunedRows());
        assertEquals(1, store.count());
        // pruned rows leave the list/search surface but stay readable
        // for provenance (US-14.10) — byCanonicalId still resolves
        assertTrue(store.byCanonicalId(cid).isPresent());
        assertTrue(store.search("Joint", new es.jllopez.neis.domain.model.NewsEventQuery(
                null, null, null, null, 20)).isEmpty());
    }

    @Test
    void zeroMatchConfirmedPruneIsNoOpSuccess() {
        seed("ADDR-001", "news", "Budget cut", "https://x.eu/1", "Jul 16, 2026");

        var outcome = service.bulkPrune(new BulkPruneSelection(
                "ADDR-009", null, null, null, null, "all", null, "cleanup"));

        assertEquals(0, outcome.prunedRecords());
        assertEquals(0, outcome.prunedRows());
        assertEquals(1, store.count());
    }

    @Test
    void unknownExplicitIdsAreReportedNotSilentlyDropped() {
        seed("ADDR-001", "news", "Budget cut", "https://x.eu/1", "Jul 16, 2026");

        var outcome = service.bulkPrune(new BulkPruneSelection(
                null, null, null, null, null, "all",
                List.of("cn-doesnotexist"), "cleanup"));

        assertEquals(0, outcome.prunedRecords());
        assertEquals(List.of("cn-doesnotexist"), outcome.unknownCanonicalIds());
    }

    @Test
    void termsSelectionPrunesOnlyTheMatches() {
        seed("ADDR-001", "news", "Budget cut announced", "https://x.eu/1", "Jul 16, 2026");
        seed("ADDR-001", "news", "New call for proposals", "https://x.eu/2", "Jul 17, 2026");

        var outcome = service.bulkPrune(new BulkPruneSelection(
                null, null, null, null, "budget", "all", null, "dupes"));

        assertEquals(1, outcome.prunedRecords());
        assertEquals(1, outcome.prunedRows());
        assertEquals(1, store.count());
    }

    @Test
    void completenessSelectionPrunesOnlyIncompleteRecords() {
        seed("ADDR-001", "news", "Complete item", "https://x.eu/1", "Jul 16, 2026");
        // no date -> missing mandatory -> incomplete (US-14.4)
        seed("ADDR-001", "news", "Incomplete item", "https://x.eu/2", null);

        var outcome = service.bulkPrune(new BulkPruneSelection(
                "ADDR-001", null, null, null, null, "incomplete", null,
                "selector defect"));

        assertEquals(1, outcome.prunedRecords());
        var survivor = store.search(new es.jllopez.neis.domain.model.NewsEventQuery(
                null, null, null, null, 20));
        assertEquals(1, survivor.size());
        assertEquals("Complete item", survivor.get(0).title());
    }

    @Test
    void invalidDateBoundIsRefusedLoud() {
        var selection = new BulkPruneSelection("ADDR-001", null,
                "not-a-date", null, null, "all", null, "cleanup");
        // dry-run path: the typo must not silently deactivate the filter
        assertThrows(IllegalArgumentException.class,
                () -> service.bulkPrunePreview(selection));
        assertThrows(IllegalArgumentException.class,
                () -> service.bulkPrune(selection));
    }

    @Test
    void emptySelectionIsRefusedOnConfirm() {
        var selection = new BulkPruneSelection(null, null, null, null,
                null, "all", null, "cleanup");
        assertThrows(IllegalArgumentException.class,
                () -> service.bulkPrune(selection));
    }

    @Test
    void reasonIsRequiredOnConfirm() {
        seed("ADDR-001", "news", "Budget cut", "https://x.eu/1", "Jul 16, 2026");
        var selection = new BulkPruneSelection("ADDR-001", null, null,
                null, null, "all", null, null);
        assertThrows(IllegalArgumentException.class,
                () -> service.bulkPrune(selection));
        // ... but the dry run may omit it
        assertEquals(1, service.bulkPrunePreview(selection).matchedRecords());
    }

    @Test
    void rePruneOfTheSameSelectionIsANoOp() {
        seed("ADDR-001", "news", "Budget cut", "https://x.eu/1", "Jul 16, 2026");
        var selection = new BulkPruneSelection("ADDR-001", null, null,
                null, null, "all", null, "dupes");

        var first = service.bulkPrune(selection);
        var second = service.bulkPrune(selection);

        assertEquals(1, first.prunedRows());
        // the first prune's timestamp and reason stand (US-14.10)
        assertEquals(0, second.prunedRecords());
        assertEquals(0, second.prunedRows());
    }
}
