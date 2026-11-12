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
 * runnable units behind the executor. The step codes are the SSAFP
 * pipeline's stages, so running a step means performing the
 * pipeline UP TO AND INCLUDING that step — every stage consumes the
 * previous one's output:
 *
 * <pre>
 * WEBSCRAPE -> SIMPLIFY -> ANALYZE -> FILTER -> PRINT
 * CHAIN = PRINT (the whole pipeline)
 * </pre>
 *
 * <p>Artifacts land in the same per-address work tree the pipeline
 * uses; the summary string becomes the execution record's outcome.
 * This service reaches the pipeline service's stage methods through
 * package-private access — same application layer, no new port: the
 * stages are application orchestration, not external adapters.</p>
 */
@Service
public class TaskWorkService {

    private final PipelineService pipeline;
    private final AddressCatalogService addressCatalog;

    public TaskWorkService(PipelineService pipeline, AddressCatalogService addressCatalog) {
        this.pipeline = pipeline;
        this.addressCatalog = addressCatalog;
    }

    /**
     * Runs the task type over the address and returns the outcome
     * summary for the execution record.
     */
    public String execute(String taskCode, Address address) {
        return execute(taskCode, address, Map.of(), null);
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
        return execute(taskCode, address, parameters, null);
    }

    /**
     * Same as {@link #execute(String, Address, Map)} with the name of
     * the chain whose step is being executed (null for single task
     * runs); the PRINT report artifact is titled with it.
     */
    public String execute(String taskCode, Address address,
                          Map<String, String> parameters, String chainName) {
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
            case "PRINT", "CHAIN" -> fullChain(address, parameters, chainName);
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
        var scrape = pipeline.scrapePort.scrape(address);
        Path artifact = pipeline.writeArtifact(address, "WEBSCRAPE", "listing-html",
                "scrape.html", scrape.html());
        return "WEBSCRAPE: " + scrape.sizeBytes() + " bytes -> " + artifact;
    }

    /** SIMPLIFY (US-07 slice): + cleanse to action/data elements. */
    private String simplify(Address address) {
        var scrape = pipeline.scrapePort.scrape(address);
        pipeline.writeArtifact(address, "WEBSCRAPE", "listing-html",
                "scrape.html", scrape.html());
        var simplified = pipeline.simplifyPort.simplify(scrape.html());
        Path artifact = pipeline.writeArtifact(address, "SIMPLIFY", "simplified-html",
                "simplified.html", simplified.html());
        return "SIMPLIFY: " + scrape.sizeBytes() + " -> " + simplified.sizeBytes()
                + " bytes (cap 102400) -> " + artifact;
    }

    /** ANALYZE (US-12/13/17 slice): + pagination, selector, capabilities. */
    private String analyze(Address address) {
        var scrape = pipeline.scrapePort.scrape(address);
        pipeline.writeArtifact(address, "WEBSCRAPE", "listing-html",
                "scrape.html", scrape.html());
        var simplified = pipeline.simplifyPort.simplify(scrape.html());
        pipeline.writeArtifact(address, "SIMPLIFY", "simplified-html",
                "simplified.html", simplified.html());
        PaginationInfo pagination = pipeline.paginationDetector.detect(simplified.html());
        PipelineService.SelectorSelection selection =
                pipeline.selectSelector(address, simplified.html());
        PipelineService.CapabilityDetection capabilities =
                pipeline.detectCapabilities(address, simplified.html());
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
        var scrape = pipeline.scrapePort.scrape(address);
        pipeline.writeArtifact(address, "WEBSCRAPE", "listing-html",
                "scrape.html", scrape.html());
        var simplified = pipeline.simplifyPort.simplify(scrape.html());
        pipeline.writeArtifact(address, "SIMPLIFY", "simplified-html",
                "simplified.html", simplified.html());
        PipelineService.SelectorSelection selection =
                pipeline.selectSelector(address, simplified.html());
        PipelineService.CapabilityDetection capabilities =
                pipeline.detectCapabilities(address, simplified.html());
        Address flagged = address.withCapabilities(capabilities.flags());
        // Persist the detection (US-17; deferred slice-3 review finding):
        // the FILTERING flag used to live only in this method's local
        // variable. The merge use case keeps flags a later run detects.
        addressCatalog.updateCapabilities(address.id(), capabilities.flags());
        if (!flagged.hasCapability(AddressCapability.FILTERING)) {
            return "FILTER: skipped — address not flagged with the filtering capability "
                    + "(detected: " + capabilities.flags() + ")";
        }
        PipelineService.FilterOutcome outcome =
                pipeline.extractFilters(flagged, simplified.html());
        StringBuilder summary = new StringBuilder("FILTER: ")
                .append(outcome.filters().size()).append(" definitions (")
                .append(outcome.source() == null ? "no zone" : outcome.source()).append(")");
        if (!outcome.filters().isEmpty()) {
            PipelineService.FilterApplication application =
                    pipeline.applyFilter(flagged, selection, outcome);
            summary.append("; applied ").append(application.field()).append("=")
                    .append(application.value())
                    .append(" -> ").append(application.items().size()).append(" items");
        }
        return summary.toString();
    }

    /** PRINT / CHAIN: the whole pipeline (WEBSCRAPE - SIMPLIFY -
     * ANALYZE - FILTER - PRINT), with the per-task parameters of
     * US-08.3 (issue #35). The readable report is persisted as a
     * PRINT task output in the content tree (US-11) and named after
     * the chain when the run is a chain's step. */
    private String fullChain(Address address, Map<String, String> parameters,
                             String chainName) {
        // Whitelist, not lookup-and-ignore: an unknown key would
        // otherwise be silently dead configuration — the exact
        // fail-open the US-08 review disqualified (a typo like
        // 'max-ites' would just do nothing).
        for (String key : parameters.keySet()) {
            if (!"max-items".equals(key) && !"max-pages".equals(key)) {
                throw new IllegalArgumentException("Task type PRINT/CHAIN accepts only "
                        + "max-items and max-pages (got: " + key + ")");
            }
        }
        int maxItems = intParameter(parameters, "max-items", 50, 1, 10_000);
        int maxPages = intParameter(parameters, "max-pages", 10, 1, 100);
        PipelineResult result = pipeline.run(address, maxItems, maxPages);
        String report = PipelineReport.render(
                chainName != null ? chainName : "PRINT", result);
        Path reportArtifact = pipeline.writeArtifact(address, "PRINT", "report",
                "report.txt", report);
        return "CHAIN: " + result.items().size() + " items, pages=" + result.pagesScraped()
                + ", classification=" + (result.analysis() == null ? "n/a" : "done")
                + ", capabilities=" + result.capabilities()
                + ", scrape=" + result.scrapeArtifact()
                + ", simplified=" + result.simplifyArtifact()
                + ", report=" + reportArtifact;
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
