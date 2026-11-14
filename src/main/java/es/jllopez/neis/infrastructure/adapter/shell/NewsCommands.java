package es.jllopez.neis.infrastructure.adapter.shell;

import es.jllopez.neis.application.NewsEventService;
import es.jllopez.neis.application.NewsEventService.ProvenanceGap;
import org.springframework.shell.core.command.annotation.Command;
import org.springframework.shell.core.command.annotation.CommandGroup;
import org.springframework.shell.core.command.annotation.Option;
import org.springframework.stereotype.Component;

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
