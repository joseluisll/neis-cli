package es.jllopez.neis.application;

import es.jllopez.neis.domain.model.AddressCapability;
import es.jllopez.neis.domain.model.FilterDefinition;
import es.jllopez.neis.domain.model.NewsEventItem;

/**
 * The readable report of one pipeline run (the PRINT step's output):
 * sizes, analysis, capabilities, filters and the discovered items.
 * Presentation lives in the application layer so the executor can
 * persist the report as a PRINT task output (US-11 content tree)
 * instead of it existing only as shell output of the retired MVP
 * command.
 */
final class PipelineReport {

    private PipelineReport() {
    }

    /**
     * Renders the report of one full pipeline run. {@code title} names
     * what produced it — the chain name when the run is a configured
     * chain's PRINT step, or the task code for a single PRINT task.
     */
    static String render(String title, PipelineResult r) {
        StringBuilder out = new StringBuilder();
        out.append(title).append(" for ").append(r.address().id())
                .append(" — ").append(r.address().name()).append("\n");
        out.append("  URL: ").append(r.address().url()).append("\n");

        out.append("SCRAPE   : ").append(r.scrapeSizeBytes())
                .append(" bytes -> ").append(r.scrapeArtifact()).append("\n");
        out.append("SIMPLIFY : ").append(r.simplifiedSizeBytes())
                .append(" bytes (cap 102400) -> ").append(r.simplifyArtifact()).append("\n");

        out.append("ANALYZE  : pagination ")
                .append(r.pagination().paginated() ? "DETECTED" : "not detected");
        if (r.pagination().paginated()) {
            out.append(" (").append(r.pagination().evidence()).append(")");
        }
        out.append("\n");
        out.append("           classification: ").append(r.analysis().classification())
                .append(" [").append(switch (r.selectorSource()) {
                    case "ai" -> "AI";
                    case "stored" -> "stored";
                    default -> "fallback";
                }).append("]\n");
        out.append("           selectors: news=").append(nvl(r.analysis().newsSelector()))
                .append(", events=").append(nvl(r.analysis().eventsSelector())).append("\n");
        out.append("           used selector: ").append(r.selectedSelector())
                .append(" (").append(switch (r.selectorSource()) {
                    case "stored" -> "stored with the source; AI skipped";
                    case "ai" -> "AI-identified, validated, stored with the source";
                    default -> "heuristic fallback";
                }).append(")\n");

        // Capability flags set by ANALYZE (US-17) and the blocks they gate.
        out.append("ANALYZE  : capabilities flagged: ");
        out.append(r.capabilities().isEmpty() ? "(none)" : String.join(", ",
                r.capabilities().stream().map(Enum::name).sorted().toList()));
        if (r.capabilityEvidence() != null && !r.capabilityEvidence().isBlank()) {
            out.append(" (").append(r.capabilityEvidence()).append(")");
        }
        out.append("\n");

        out.append("FILTER   : ");
        if ("skipped".equals(r.filterSource())) {
            out.append("skipped — ").append(r.filterNote()).append("\n");
        } else {
            out.append(r.filters().size()).append(" filter field(s) available [")
                    .append(r.filterSource()).append("]\n");
            if (r.filterZoneSelector() != null) {
                out.append("           filter zone: ").append(r.filterZoneSelector())
                        .append(" (identified by ").append(r.filterSource())
                        .append(", persisted with the address)\n");
            }
            if (r.filterNote() != null) {
                out.append("           note: ").append(r.filterNote()).append("\n");
            }
            for (FilterDefinition f : r.filters()) {
                out.append("           - ").append(f.name()).append(": ")
                        .append(f.values().isEmpty() ? "(free text)"
                                : String.join(" | ", f.values())).append("\n");
            }
        }

        if (r.pageUrlPattern() != null) {
            out.append("           page URL pattern: ").append(r.pageUrlPattern()).append("\n");
        }
        if (r.correctedAddress() != null) {
            out.append("           address corrected by ANALYZE: ")
                    .append(r.address().url()).append("\n");
        }
        if (r.capabilities().contains(AddressCapability.PAGINATION)) {
            out.append("           pagination iteration: ").append(r.pagesScraped())
                    .append(" page(s) scraped (gated on the PAGINATION flag)\n");
        }

        // FILTER application (US-06.6 slice): one criterion applied by
        // the SCRAPE connector before the filtered extraction.
        if (r.appliedFilterField() != null) {
            out.append("APPLY    : ").append(r.appliedFilterField()).append(" = ")
                    .append(r.appliedFilterValue()).append(" [")
                    .append(r.filterRequest()).append("]\n");
            out.append("           filtered scrape: ").append(r.filteredScrapeArtifact())
                    .append("\n");
            out.append("PRINT(f) : ").append(r.filteredItems().size())
                    .append(" ").append(r.address().itemKind())
                    .append(" item(s) in the FILTERED listing (unfiltered: ")
                    .append(r.items().size()).append(")\n");
            if (r.filteredItems().isEmpty()) {
                out.append("           (no items matched the selected selector ")
                        .append("in the filtered response)\n");
            }
            int k = 1;
            for (NewsEventItem item : r.filteredItems()) {
                out.append("  ").append(k++).append(". ").append(item.title()).append("\n");
                if (item.date() != null) {
                    out.append("     date: ").append(item.date()).append("\n");
                }
                if (item.url() != null) {
                    out.append("     link: ").append(item.url()).append("\n");
                }
            }
        } else if (r.filterApplicationNote() != null) {
            out.append("APPLY    : skipped — ").append(r.filterApplicationNote()).append("\n");
        }

        if (!r.analysis().notes().isBlank()) {
            out.append("           notes: ").append(r.analysis().notes()).append("\n");
        }

        out.append("PRINT    : ").append(r.items().size())
                .append(" ").append(r.address().itemKind()).append(" item(s) discovered\n");
        if (r.items().isEmpty()) {
            out.append("           (no items matched the selected selector)\n");
        }
        int i = 1;
        for (NewsEventItem item : r.items()) {
            out.append("  ").append(i++).append(". ").append(item.title()).append("\n");
            if (item.date() != null) {
                out.append("     date: ").append(item.date()).append("\n");
            }
            if (item.url() != null) {
                out.append("     link: ").append(item.url()).append("\n");
            }
            if (item.description() != null && !item.description().isBlank()) {
                out.append("     desc: ").append(item.description()).append("\n");
            }
        }
        return out.toString();
    }

    private static String nvl(String s) {
        return s == null || s.isBlank() ? "-" : s;
    }
}
