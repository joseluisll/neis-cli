package es.jllopez.neis.infrastructure.adapter.shell;

import es.jllopez.neis.application.ContentStoreService;
import es.jllopez.neis.application.NewsEventService;
import es.jllopez.neis.domain.model.NewsEventItem;
import es.jllopez.neis.testsupport.InMemoryContentStorePort;
import es.jllopez.neis.testsupport.InMemoryExecutionStorePort;
import es.jllopez.neis.testsupport.InMemoryNewsEventStorePort;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code news sightings} listing over the limit (US-14.5 review
 * finding, cleared): the store's limit counts stored ROWS, so a page
 * full of duplicates of one logical record used to under-fill the
 * request — the command now grows the fetch until the limit holds
 * LOGICAL records or the store is exhausted.
 */
class NewsCommandsTest {

    private final InMemoryNewsEventStorePort store = new InMemoryNewsEventStorePort();
    private final InMemoryExecutionStorePort executions =
            new InMemoryExecutionStorePort(List.of("WEBSCRAPE"));
    private final InMemoryContentStorePort contents = new InMemoryContentStorePort();
    private final NewsEventService service = new NewsEventService(store,
            executions, new ContentStoreService(contents, "target/test-content"));
    private final NewsCommands commands = new NewsCommands(service,
            new es.jllopez.neis.application.NewsEventExportService(store));

    private void seedDuplicatesOnTop() {
        // Six DISTINCT logical records B..G first, then the SAME story
        // A captured from three addresses: newest first the store then
        // reads A, A, A, G, F, E, D, C, B — the top page (limit 5) is
        // 3 duplicates + 2 logical, while 7 logical records exist.
        for (char c = 'B'; c <= 'G'; c++) {
            store.saveAll("ADDR-001", "news", List.of(new NewsEventItem(
                    "ADDR-001", "news", "Story " + c, "desc " + c,
                    "https://x.eu/" + c, "2026-11-01")), "exec-1", "CONT-1");
        }
        for (int i = 1; i <= 3; i++) {
            store.saveAll("ADDR-00" + i, "news", List.of(new NewsEventItem(
                    "ADDR-00" + i, "news", "Story A", "desc A",
                    "https://x.eu/A", "2026-11-02")), "exec-2", "CONT-2");
        }
    }

    @Test
    void duplicatesInsideThePageNoLongerUnderFillTheLimit() {
        seedDuplicatesOnTop();

        String out = commands.sightings(null, 5);

        assertTrue(out.startsWith("Sighting history (5 items):\n"),
                "the limit holds LOGICAL records, got: " + out.lines().findFirst());
        // The five logical records shown are A and the five newest of
        // B..G (order stable, growing page only appends): the story of
        // the oldest record (B) stays outside the limit.
        assertTrue(out.contains("Story A"));
        assertTrue(out.contains("Story G"));
        assertFalse(out.contains("Story B"), "records beyond the limit stay hidden");
        // Every logical record appears once, its 3 duplicate sightings
        // grouped into one entry (A) and one per capture for the rest.
        assertEquals(5, out.split("\n  cn-", -1).length - 1,
                "one record line per logical record:\n" + out);
    }

    @Test
    void exhaustingTheStoreRendersEveryLogicalRecordRegardlessOfLimit() {
        seedDuplicatesOnTop();

        String out = commands.sightings(null, 50);

        assertTrue(out.startsWith("Sighting history (7 items):\n"),
                "all 7 logical records render when the store runs out, "
                        + "got: " + out.lines().findFirst());
        assertTrue(out.contains("Story B"));
        assertTrue(out.contains("Story G"));
        // A is one logical record with three sightings (one per address).
        assertEquals(7, out.split("\n  cn-", -1).length - 1,
                "one record line per logical record:\n" + out);
    }
}
