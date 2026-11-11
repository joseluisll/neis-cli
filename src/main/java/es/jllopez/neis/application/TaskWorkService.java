package es.jllopez.neis.application;

import es.jllopez.neis.domain.model.Address;
import es.jllopez.neis.domain.model.AddressCapability;
import es.jllopez.neis.domain.model.PaginationInfo;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

/**
 * Executes one TASK TYPE over one address (US-05, epic #5): the
 * runnable units behind the executor. The step codes are the MVP
 * chain's stages (issue #90), so running a step means performing the
 * pipeline UP TO AND INCLUDING that step — every stage consumes the
 * previous one's output:
 *
 * <pre>
 * WEBSCRAPE -> SIMPLIFY -> ANALYZE -> FILTER -> PRINT
 * CHAIN = PRINT (the whole predefined chain)
 * </pre>
 *
 * <p>Artifacts land in the same per-address work tree the chain uses;
 * the summary string becomes the execution record's outcome. This
 * service reaches the chain service's stage methods through
 * package-private access — same application layer, no new port: the
 * stages are application orchestration, not external adapters.</p>
 */
@Service
public class TaskWorkService {

    private final MvpChainService chain;
    private final String workDir;

    public TaskWorkService(MvpChainService chain) {
        this.chain = chain;
        this.workDir = chain.workDir;
    }

    /**
     * Runs the task type over the address and returns the outcome
     * summary for the execution record.
     */
    public String execute(String taskCode, Address address) {
        return switch (taskCode) {
            case "WEBSCRAPE" -> scrape(address);
            case "SIMPLIFY" -> simplify(address);
            case "ANALYZE" -> analyze(address);
            case "FILTER" -> filter(address);
            case "PRINT", "CHAIN" -> fullChain(address);
            default -> throw new IllegalArgumentException(
                    "Unknown task type: " + taskCode
                            + " (known: WEBSCRAPE, SIMPLIFY, ANALYZE, FILTER, PRINT, CHAIN)");
        };
    }

    /** WEBSCRAPE (US-06 slice): obtain and store the listing HTML5. */
    private String scrape(Address address) {
        var scrape = chain.scrapePort.scrape(address);
        Path artifact = chain.writeArtifact(chain.addressDir(address), "scrape.html",
                scrape.html());
        return "WEBSCRAPE: " + scrape.sizeBytes() + " bytes -> " + artifact;
    }

    /** SIMPLIFY (US-07 slice): + cleanse to action/data elements. */
    private String simplify(Address address) {
        var scrape = chain.scrapePort.scrape(address);
        chain.writeArtifact(chain.addressDir(address), "scrape.html", scrape.html());
        var simplified = chain.simplifyPort.simplify(scrape.html());
        Path artifact = chain.writeArtifact(chain.addressDir(address), "simplified.html",
                simplified.html());
        return "SIMPLIFY: " + scrape.sizeBytes() + " -> " + simplified.sizeBytes()
                + " bytes (cap 102400) -> " + artifact;
    }

    /** ANALYZE (US-12/13/17 slice): + pagination, selector, capabilities. */
    private String analyze(Address address) {
        var scrape = chain.scrapePort.scrape(address);
        chain.writeArtifact(chain.addressDir(address), "scrape.html", scrape.html());
        var simplified = chain.simplifyPort.simplify(scrape.html());
        chain.writeArtifact(chain.addressDir(address), "simplified.html", simplified.html());
        PaginationInfo pagination = chain.paginationDetector.detect(simplified.html());
        MvpChainService.SelectorSelection selection =
                chain.selectSelector(address, simplified.html());
        MvpChainService.CapabilityDetection capabilities =
                chain.detectCapabilities(address, simplified.html());
        return "ANALYZE: classification=" + selection.analysis().classification()
                + ", selector=" + selection.selector() + " (" + selection.source() + ")"
                + ", pagination=" + pagination.paginated()
                + ", capabilities=" + capabilities.flags();
    }

    /** FILTER (US-17/US-06.6 slice): + filter extraction and application. */
    private String filter(Address address) {
        var scrape = chain.scrapePort.scrape(address);
        chain.writeArtifact(chain.addressDir(address), "scrape.html", scrape.html());
        var simplified = chain.simplifyPort.simplify(scrape.html());
        chain.writeArtifact(chain.addressDir(address), "simplified.html", simplified.html());
        MvpChainService.SelectorSelection selection =
                chain.selectSelector(address, simplified.html());
        MvpChainService.CapabilityDetection capabilities =
                chain.detectCapabilities(address, simplified.html());
        Address flagged = address.withCapabilities(capabilities.flags());
        if (!flagged.hasCapability(AddressCapability.FILTERING)) {
            return "FILTER: skipped — address not flagged with the filtering capability "
                    + "(detected: " + capabilities.flags() + ")";
        }
        MvpChainService.FilterOutcome outcome =
                chain.extractFilters(flagged, simplified.html());
        StringBuilder summary = new StringBuilder("FILTER: ")
                .append(outcome.filters().size()).append(" definitions (")
                .append(outcome.source() == null ? "no zone" : outcome.source()).append(")");
        if (!outcome.filters().isEmpty()) {
            MvpChainService.FilterApplication application =
                    chain.applyFilter(flagged, selection, outcome);
            summary.append("; applied ").append(application.field()).append("=")
                    .append(application.value())
                    .append(" -> ").append(application.items().size()).append(" items");
        }
        return summary.toString();
    }

    /** PRINT / CHAIN: the whole predefined chain (issue #90). */
    private String fullChain(Address address) {
        MvpChainResult result = chain.run(address);
        return "CHAIN: " + result.items().size() + " items, pages=" + result.pagesScraped()
                + ", classification=" + (result.analysis() == null ? "n/a" : "done")
                + ", capabilities=" + result.capabilities()
                + ", scrape=" + result.scrapeArtifact()
                + ", simplified=" + result.simplifyArtifact();
    }
}
