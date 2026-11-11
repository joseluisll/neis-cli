package es.jllopez.neis.infrastructure.adapter.shell;

import es.jllopez.neis.application.AddressCatalogService;
import es.jllopez.neis.application.MvpChainResult;
import es.jllopez.neis.application.MvpChainService;
import es.jllopez.neis.domain.model.Address;
import es.jllopez.neis.domain.model.NewsEventItem;
import org.springframework.shell.core.command.annotation.Argument;
import org.springframework.shell.core.command.annotation.Command;
import org.springframework.shell.core.command.annotation.CommandGroup;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Driving adapter: Spring Shell command that invokes the predefined
 * static MVP chain (issue #90) and prints the result
 * (the PRINT step: list of discovered news/events with metadata).
 */
@Component
@CommandGroup(name = "MVP", description = "MVP static chain commands")
public class MvpCommands {

    private final MvpChainService chainService;
    private final AddressCatalogService addressCatalog;

    public MvpCommands(MvpChainService chainService, AddressCatalogService addressCatalog) {
        this.chainService = chainService;
        this.addressCatalog = addressCatalog;
    }

    @Command(name = "mvp-chain", group = "MVP",
            description = "Run the predefined static chain "
                    + "SCRAPE -> SIMPLIFY -> ANALYZE -> PRINT for catalog address(es)")
    public String mvpChain(
            @Argument(index = 0, description = "Address id (ADDR-001..ADDR-004) or 'all'",
                    defaultValue = "ADDR-001") String target) {
        boolean all = "all".equalsIgnoreCase(target);
        List<MvpChainResult> results = all
                ? chainService.runAll()
                : List.of(chainService.run(addressCatalog.byId(target)));

        StringBuilder out = new StringBuilder();
        for (MvpChainResult result : results) {
            if (!out.isEmpty()) {
                out.append("\n").append("=".repeat(72)).append("\n");
            }
            out.append(render(result));
        }
        return out.toString();
    }

    @Command(name = "mvp-addresses", group = "MVP",
            description = "List the static MVP address catalog (seed data of issue #62)")
    public String mvpAddresses() {
        StringBuilder out = new StringBuilder("Static MVP address catalog:\n");
        for (Address address : addressCatalog.list()) {
            out.append("  ").append(address.id())
                    .append("  [").append(address.itemKind()).append("]  ")
                    .append(address.name()).append("\n")
                    .append("        ").append(address.url()).append("\n");
        }
        return out.toString();
    }

    private String render(MvpChainResult r) {
        StringBuilder out = new StringBuilder();
        out.append("MVP chain for ").append(r.address().id())
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
                    case "stored", "ai" -> "AI";
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

    private String nvl(String s) {
        return s == null || s.isBlank() ? "-" : s;
    }
}
