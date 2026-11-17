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
    void completenessFilterNarrowsTheListingBeforeTheLimit() {
        // US-16.4 review finding #97: completeness was not filterable
        // outside news export. The filter narrows the unbounded match
        // BEFORE the limit — an incomplete-only page is not cut short
        // by the newest complete rows.
        store.saveAll("ADDR-001", "news", List.of(
                new NewsEventItem("ADDR-001", "news", "Complete story",
                        "desc", "https://x.eu/complete", "2026-11-02")), null, null);
        store.saveAll("ADDR-001", "news", List.of(
                new NewsEventItem("ADDR-001", "news", "Gappy story",
                        null, "https://x.eu/gappy", null)), null, null);

        String incompleteOnly = commands.list(null, null, null, null, "incomplete", 20);
        assertTrue(incompleteOnly.contains("Gappy story"), incompleteOnly);
        assertFalse(incompleteOnly.contains("Complete story"), incompleteOnly);
        assertTrue(incompleteOnly.startsWith("News/events (1):"), incompleteOnly);
        assertTrue(incompleteOnly.contains("INCOMPLETE"), incompleteOnly);

        String completeOnly = commands.list(null, null, null, null, "complete", 20);
        assertTrue(completeOnly.contains("Complete story"), completeOnly);
        assertFalse(completeOnly.contains("Gappy story"), completeOnly);

        // Limit applies to the FILTERED set, not the raw match.
        String limited = commands.list(null, null, null, null, "incomplete", 1);
        assertTrue(limited.startsWith("News/events (1):"), limited);

        String invalid = commands.list(null, null, null, null, "nonsense", 20);
        assertTrue(invalid.startsWith("Invalid --incomplete value"), invalid);
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
    void nonPositiveLimitFallsBackToTheDefaultInsteadOfAnEmptyPage() {
        // 20dc27b review finding: limit 0 used to render 'Sighting
        // history (0 items)' with a misleading <no stored news/events>
        // note while data existed — it now coerces to the store query's
        // documented default (20), like news list/search do.
        seedDuplicatesOnTop();

        String out = commands.sightings(null, 0);

        assertTrue(out.startsWith("Sighting history (7 items):\n"),
                "limit 0 shows the store's content, got: " + out.lines().findFirst());
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

    @Test
    void theEmptyMessageNamesTheActiveFilters() {
        // US-16.8 (issue #104): a filter that excluded the remaining
        // rows (--incomplete complete over an all-incomplete store) is
        // NAMED in the empty-result message, not hidden behind
        // "no stored news/events match".
        store.saveAll("ADDR-001", "news", List.of(
                new NewsEventItem("ADDR-001", "news", "Gappy story",
                        null, "https://x.eu/gappy", null)), null, null);

        String out = commands.list(null, null, null, null, "complete", 20);

        assertTrue(out.contains("<no stored news/events match"), out);
        assertTrue(out.contains("completeness complete"), out);
    }
}
