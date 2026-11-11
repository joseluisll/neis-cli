package es.jllopez.neis.application;

import es.jllopez.neis.domain.model.Address;
import es.jllopez.neis.domain.model.AddressCapability;
import es.jllopez.neis.domain.model.PaginationInfo;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

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
    private final AddressCatalogService addressCatalog;
    private final String workDir;

    public TaskWorkService(MvpChainService chain, AddressCatalogService addressCatalog) {
        this.chain = chain;
        this.addressCatalog = addressCatalog;
        this.workDir = chain.workDir;
    }

    /**
     * Runs the task type over the address and returns the outcome
     * summary for the execution record.
     */
    public String execute(String taskCode, Address address) {
        return execute(taskCode, address, Map.of());
    }

    /**
     * Runs the task type over the address with per-task parameters
     * (US-08.3, issue #35). PRINT and CHAIN accept {@code max-items}
     * (1..10000, the item cap; default 50) and {@code max-pages}
     * (1..100, the pagination-iteration guard; default 10); any other
     * parameter key is refused. WEBSCRAPE, SIMPLIFY, ANALYZE and
     * FILTER take no parameters yet — their parameter needs arrive
     * with the selector and extraction slices (US-12), and passing
     * one before then is an error, not silent dead configuration.
     */
    public String execute(String taskCode, Address address,
                          Map<String, String> parameters) {
        return switch (taskCode) {
            case "WEBSCRAPE" -> {
                requireNoParameters(taskCode, parameters);
                yield scrape(address);
            }
            case "SIMPLIFY" -> {
                requireNoParameters(taskCode, parameters);
                yield simplify(address);
            }
            case "ANALYZE" -> {
                requireNoParameters(taskCode, parameters);
                yield analyze(address);
            }
            case "FILTER" -> {
                requireNoParameters(taskCode, parameters);
                yield filter(address);
            }
            case "PRINT", "CHAIN" -> fullChain(address, parameters);
            default -> throw new IllegalArgumentException(
                    "Unknown task type: " + taskCode
                            + " (known: WEBSCRAPE, SIMPLIFY, ANALYZE, FILTER, PRINT, CHAIN)");
        };
    }

    private void requireNoParameters(String taskCode, Map<String, String> parameters) {
        if (!parameters.isEmpty()) {
            throw new IllegalArgumentException("Task type " + taskCode
                    + " takes no parameters (got: " + parameters.keySet() + ")");
        }
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
        // Persist the detection (US-17; deferred slice-3 review finding):
        // the flags used to live only in this method's local variable.
        addressCatalog.updateCapabilities(address.id(), capabilities.flags());
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
        // Persist the detection (US-17; deferred slice-3 review finding):
        // the FILTERING flag used to live only in this method's local
        // variable. The merge use case keeps flags a later run detects.
        addressCatalog.updateCapabilities(address.id(), capabilities.flags());
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

    /** PRINT / CHAIN: the whole predefined chain (issue #90), with
     * the per-task parameters of US-08.3 (issue #35). */
    private String fullChain(Address address, Map<String, String> parameters) {
        int maxItems = intParameter(parameters, "max-items", 50, 1, 10_000);
        int maxPages = intParameter(parameters, "max-pages", 10, 1, 100);
        MvpChainResult result = chain.run(address, maxItems, maxPages);
        return "CHAIN: " + result.items().size() + " items, pages=" + result.pagesScraped()
                + ", classification=" + (result.analysis() == null ? "n/a" : "done")
                + ", capabilities=" + result.capabilities()
                + ", scrape=" + result.scrapeArtifact()
                + ", simplified=" + result.simplifyArtifact();
    }

    /** One bounded integer parameter, or its default when absent. */
    private int intParameter(Map<String, String> parameters, String key,
                             int defaultValue, int min, int max) {
        String raw = parameters.get(key);
        if (raw == null) {
            return defaultValue;
        }
        int value;
        try {
            value = Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "Parameter '" + key + "' must be an integer, got: " + raw);
        }
        if (value < min || value > max) {
            throw new IllegalArgumentException("Parameter '" + key
                    + "' must be between " + min + " and " + max + ", got: " + value);
        }
        return value;
    }
}
