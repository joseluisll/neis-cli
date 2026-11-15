package es.jllopez.neis.infrastructure.adapter.shell;

import es.jllopez.neis.application.NewsEventService;
import es.jllopez.neis.application.NewsEventService.ProvenanceGap;
import es.jllopez.neis.domain.model.NewsEventItem;
import es.jllopez.neis.domain.model.NewsEventQuery;
import es.jllopez.neis.domain.model.NewsEventSighting;
import org.springframework.shell.core.command.annotation.Command;
import org.springframework.shell.core.command.annotation.CommandGroup;
import org.springframework.shell.core.command.annotation.Option;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

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
                    description = "Items dated on/after this date — ISO (yyyy-MM-dd) or "
                            + "the extractor's form ('Jul 16, 2026'); rows without a "
                            + "parseable date are excluded") String dateFrom,
            @Option(longName = "to",
                    description = "Items dated on/before this date (same accepted forms "
                            + "as --from)") String dateTo,
            @Option(longName = "limit", defaultValue = "20",
                    description = "Maximum number of items to show") int limit) {
        // A typo in a range bound must not silently deactivate the
        // filter (US-14.3 review finding): validate what the user
        // passed before it reaches the lenient query parser.
        for (String[] bound : new String[][] {{"--from", dateFrom}, {"--to", dateTo}}) {
            if (bound[1] != null && !bound[1].isBlank()
                    && NewsEventQuery.parseLenient(bound[1]) == null) {
                return "Invalid " + bound[0] + " date '" + bound[1]
                        + "' — use ISO (yyyy-MM-dd) or the extractor's form "
                        + "(e.g. 'Jul 16, 2026')";
            }
        }
        List<NewsEventItem> items = newsEvents.search(new NewsEventQuery(
                addressId == null ? null : addressId.trim(),
                kind == null ? null : kind.trim().toLowerCase(Locale.ROOT),
                dateFrom, dateTo, limit));
        StringBuilder out = new StringBuilder("News/events (")
                .append(items.size()).append("):\n");
        for (NewsEventItem item : items) {
            out.append("  ").append(item.date() == null ? "????-??-??" : item.date())
                    .append("  ").append(item.kind() == null ? "-" : item.kind())
                    .append("  [").append(item.addressId()).append("]  ")
                    .append(item.title() == null ? "<no title>" : item.title())
                    .append(item.url() == null ? "" : "  <" + item.url() + ">")
                    .append(provenance(item))
                    .append(incompleteness(item))
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

    /** The incompleteness suffix (US-14.4): a record missing mandatory
     * metadata is flagged with the missing fields, so selector or
     * source problems are visible in the listing itself. */
    private static String incompleteness(NewsEventItem item) {
        if (!item.incomplete()) {
            return "";
        }
        return "  INCOMPLETE(missing: "
                + (item.missingFields() == null ? "?" : item.missingFields()) + ")";
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

    @Command(name = {"news", "sightings"}, group = "News",
            description = "The appearance history of the stored news/events (US-14.5): "
                    + "grouped by canonical id (one logical record), every capture that "
                    + "produced each item with its provenance; re-encounters add sightings, "
                    + "never records. Filter by address.")
    public String sightings(
            @Option(shortName = 'a', longName = "address",
                    description = "Address id (omit for every address)") String addressId,
            @Option(longName = "limit", defaultValue = "20",
                    description = "Maximum number of items (logical records) to show") int limit) {
        List<NewsEventItem> items = newsEvents.search(new NewsEventQuery(
                addressId == null ? null : addressId.trim(), null, null, null, limit));
        StringBuilder body = new StringBuilder();
        // Cross-address dedup: rows sharing a canonical id are ONE
        // logical record — rendered once (US-14.5 review).
        java.util.Set<String> rendered = new java.util.HashSet<>();
        int shown = 0;
        for (NewsEventItem item : items) {
            if (!rendered.add(item.canonicalId())) {
                continue;
            }
            shown++;
            List<NewsEventSighting> sightings = newsEvents.sightings(item.canonicalId());
            long addresses = sightings.stream().map(NewsEventSighting::addressId)
                    .distinct().count();
            body.append("  ").append(item.canonicalId())
                    .append("  [").append(item.addressId()).append("]  ")
                    .append(item.title() == null ? "<no title>" : item.title())
                    .append("  ").append(sightings.size())
                    .append(" sighting(s) across ").append(addresses)
                    .append(" address(es)\n");
            for (NewsEventSighting sighting : sightings) {
                body.append("    seen in [").append(sighting.addressId()).append("]  ")
                        .append(sighting.seenAt())
                        .append("  {").append(sighting.executionId() == null
                                ? "-" : sighting.executionId())
                        .append(" | ").append(sighting.contentId() == null
                                ? "-" : sighting.contentId()).append("}\n");
            }
        }
        if (items.isEmpty()) {
            body.append("  <no stored news/events")
                    .append(addressId == null ? "" : " for address " + addressId)
                    .append(">\n");
            shown = 0;
        }
        return new StringBuilder("Sighting history (")
                .append(shown).append(shown == 1 ? " item" : " items")
                .append("):\n").append(body).toString();
    }
}
