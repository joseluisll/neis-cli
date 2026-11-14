package es.jllopez.neis.application;

import es.jllopez.neis.domain.model.ExecutionStatus;
import es.jllopez.neis.domain.model.NewsEventItem;
import es.jllopez.neis.domain.model.TaskExecution;
import es.jllopez.neis.domain.model.TriggerType;
import es.jllopez.neis.testsupport.InMemoryContentStorePort;
import es.jllopez.neis.testsupport.InMemoryExecutionStorePort;
import es.jllopez.neis.testsupport.InMemoryNewsEventStorePort;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The news/events query use case (US-14.3 read path): the US-14.2
 * provenance of stored rows becomes observable, and dangling
 * references (execution or content record no longer existing) are
 * flagged — the deferred finding of the US-14.2 re-review (2026-11-14).
 */
class NewsEventServiceTest {

    private final InMemoryNewsEventStorePort store = new InMemoryNewsEventStorePort();
    private final InMemoryExecutionStorePort executions =
            new InMemoryExecutionStorePort(List.of("WEBSCRAPE"));
    private final InMemoryContentStorePort contents = new InMemoryContentStorePort();
    private final NewsEventService service = new NewsEventService(store,
            executions, new ContentStoreService(contents, "target/test-content"));

    private void seedExecution(String id) {
        executions.save(new TaskExecution(id, "WEBSCRAPE", "ADDR-001", null,
                TriggerType.MANUAL, ExecutionStatus.SUCCESS,
                Instant.parse("2026-11-14T10:00:00Z"),
                Instant.parse("2026-11-14T10:00:05Z"), null));
    }

    @Test
    void allProvenanceResolvingYieldsNoGaps() {
        seedExecution("exec-1");
        contents.saveContent(new es.jllopez.neis.domain.model.ContentRecord(
                "CONT-abc", "ADDR-001", "cap-abc", "WEBSCRAPE", "listing-html",
                "/tmp/x.html", Instant.parse("2026-11-14T10:00:00Z")));
        store.saveAll("ADDR-001", "news", List.of(new NewsEventItem(
                "ADDR-001", "news", "Item", null, "https://x.eu/1", null)),
                "exec-1", "CONT-abc");

        assertTrue(service.provenanceGaps().isEmpty(),
                "existing execution + content record: no gaps");
        assertEquals(1, service.all().size());
        assertEquals("exec-1", service.all().get(0).executionId(),
                "the read path exposes the provenance (US-14.2 gap)");
    }

    @Test
    void danglingExecutionAndContentReferencesAreFlagged() {
        // The execution id points nowhere; the content id too.
        store.saveAll("ADDR-001", "news", List.of(new NewsEventItem(
                "ADDR-001", "news", "Orphan", null, "https://x.eu/2", null)),
                "exec-missing", "CONT-missing");

        List<NewsEventService.ProvenanceGap> gaps = service.provenanceGaps();
        assertEquals(2, gaps.size(), "both references dangle");
        assertTrue(gaps.stream().anyMatch(g ->
                "execution".equals(g.refType()) && "exec-missing".equals(g.refId())));
        assertTrue(gaps.stream().anyMatch(g ->
                "content".equals(g.refType()) && "CONT-missing".equals(g.refId())));
    }

    @Test
    void rowsWithoutProvenanceAreNotGaps() {
        // Migrated / pre-linking rows carry null provenance: not dangling.
        store.saveAll("ADDR-002", "news", List.of(new NewsEventItem(
                "ADDR-002", "news", "Old row", null, "https://x.eu/3", null)),
                null, null);

        assertTrue(service.provenanceGaps().isEmpty(),
                "null provenance is absence of provenance, not a gap");
    }

    @Test
    void oneGoodOneDanglingReferenceOnTheSameRow() {
        seedExecution("exec-2");
        store.saveAll("ADDR-001", "news", List.of(new NewsEventItem(
                "ADDR-001", "news", "Half-linked", null, "https://x.eu/4", null)),
                "exec-2", "CONT-gone");

        List<NewsEventService.ProvenanceGap> gaps = service.provenanceGaps();
        assertEquals(1, gaps.size());
        assertEquals("content", gaps.get(0).refType());
    }
}
