package es.jllopez.neis.infrastructure.adapter.shell;

import es.jllopez.neis.application.NewsEventService;
import es.jllopez.neis.application.NewsEventService.ProvenanceGap;
import es.jllopez.neis.domain.model.NewsEventItem;
import es.jllopez.neis.domain.model.NewsEventQuery;
import org.springframework.shell.core.command.annotation.Command;
import org.springframework.shell.core.command.annotation.CommandGroup;
import org.springframework.shell.core.command.annotation.Option;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Driving adapter: Spring Shell commands inspecting the news/events
 * repository (US-14, epic #54 — the same commands serve the CLI and
 * the interactive shell TUI).
 */
@Component
@CommandGroup(name = "News", description = "News/events repository commands (US-14)")
public class NewsCommands {

    private final NewsEventService newsEvents;

    public NewsCommands(NewsEventService newsEvents) {
        this.newsEvents = newsEvents;
    }

    @Command(name = {"news", "list"}, group = "News",
            description = "List the stored news/events, newest first, with their "
                    + "provenance (US-14.3); filter by address, kind and date range. "
                    + "The same command inside the interactive shell is the repository's "
                    + "TUI view.")
    public String list(
            @Option(shortName = 'a', longName = "address",
                    description = "Address id (omit for every address)") String addressId,
            @Option(shortName = 'k', longName = "kind",
                    description = "Item kind: news or event (omit for both)") String kind,
            @Option(longName = "from",
                    description = "Items dated on/after this ISO date (yyyy-MM-dd); "
                            + "rows without a date are excluded") String dateFrom,
            @Option(longName = "to",
                    description = "Items dated on/before this ISO date (yyyy-MM-dd)") String dateTo,
            @Option(longName = "limit", defaultValue = "20",
                    description = "Maximum number of items to show") int limit) {
        List<NewsEventItem> items = newsEvents.search(new NewsEventQuery(
                addressId, kind, dateFrom, dateTo, limit));
        StringBuilder out = new StringBuilder("News/events (")
                .append(items.size()).append("):\n");
        for (NewsEventItem item : items) {
            out.append("  ").append(item.date() == null ? "????-??-??" : item.date())
                    .append("  ").append(item.kind())
                    .append("  [").append(item.addressId()).append("]  ")
                    .append(item.title() == null ? "<no title>" : item.title())
                    .append(item.url() == null ? "" : "  <" + item.url() + ">")
                    .append(provenance(item))
                    .append("\n");
        }
        if (items.isEmpty()) {
            out.append("  <no stored news/events match")
                    .append(addressId == null ? "" : " address " + addressId)
                    .append(" — run a chain over a verified address>\n");
        }
        return out.toString();
    }

    /** The provenance suffix (US-14.2): execution and content record. */
    private static String provenance(NewsEventItem item) {
        if (item.executionId() == null && item.contentId() == null) {
            return "  (no provenance)";
        }
        return "  {" + (item.executionId() == null ? "-" : item.executionId())
                + " | " + (item.contentId() == null ? "-" : item.contentId()) + "}";
    }

    @Command(name = {"news", "provenance"}, group = "News",
            description = "Check the provenance of the stored news/events: list rows "
                    + "whose execution or content-record reference no longer resolves "
                    + "(US-14.2; empty output means every reference resolves)")
    public String provenance() {
        var items = newsEvents.all();
        long withProvenance = items.stream()
                .filter(item -> item.executionId() != null || item.contentId() != null)
                .count();
        var gaps = newsEvents.provenanceGaps();
        StringBuilder out = new StringBuilder("Provenance check over ")
                .append(items.size()).append(" stored news/event rows (")
                .append(withProvenance).append(" with provenance):\n");
        for (ProvenanceGap gap : gaps) {
            out.append("  DANGLING ").append(gap.refType()).append(" ref ")
                    .append(gap.refId()).append(" on ")
                    .append(gap.item().addressId()).append(" | ")
                    .append(gap.item().url() == null
                            ? "title:" + (gap.item().title() == null
                                    ? "<no title, no url>" : gap.item().title())
                            : gap.item().url())
                    .append("\n");
        }
        if (gaps.isEmpty()) {
            out.append("  all provenance references resolve\n");
        }
        return out.toString();
    }
}
