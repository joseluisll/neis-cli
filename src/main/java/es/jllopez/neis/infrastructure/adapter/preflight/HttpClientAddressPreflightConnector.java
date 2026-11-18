package es.jllopez.neis.infrastructure.adapter.preflight;

import es.jllopez.neis.domain.model.Address;
import es.jllopez.neis.domain.model.ScrapeResult;
import es.jllopez.neis.domain.model.WebpageType;
import es.jllopez.neis.domain.port.AddressPreflightPort;
import es.jllopez.neis.domain.port.ContentScrapePort;
import es.jllopez.neis.domain.port.WebpageTypeCatalogPort;
import es.jllopez.neis.domain.service.WebpageTypeClassifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Driving-side connector of the address preflight (US-13.6, issue
 * #65): a lightweight entry-page probe — a static HTTP fetch, plus a
 * rendered probe ONLY when the static fetch yields an application
 * shell — that records HTTP status, content type, size and shell
 * indicators, classifies the address into the webpage-type taxonomy
 * (US-06.5, deterministic heuristics; the AI may refine) and estimates
 * the retrieval connector for the executor (http vs rendered vs
 * fixtures, behind the fetching port).
 *
 * <p>The classification/estimate is a SUGGESTION the user can override
 * ('address webpage-type' / the per-run connector parameter). The
 * probe's full evidence is a JSON object string for the capture
 * metadata column {@code captures.preflight} (V18).</p>
 */
@Component
public class HttpClientAddressPreflightConnector implements AddressPreflightPort {

    private static final Logger log =
            LoggerFactory.getLogger(HttpClientAddressPreflightConnector.class);

    /** DOM markers of a client-side application shell (static fetch). */
    private static final String[] SHELL_MARKERS = {
            "<app-root", "<ng-app", "id=\"root\"", "id=\"app\"",
            "data-reactroot", "<div id=\"root\"></div>", "<noscript>"
    };

    private final ContentScrapePort scrapePort;
    private final ObjectProvider<es.jllopez.neis.infrastructure.adapter.scrape.PlaywrightRenderConnector> renderedPort;
    private final WebpageTypeCatalogPort webpageTypes;
    private final int shellSampleBytes;

    public HttpClientAddressPreflightConnector(
            ContentScrapePort scrapePort,
            org.springframework.beans.factory.ObjectProvider<es.jllopez.neis.infrastructure.adapter.scrape.PlaywrightRenderConnector> renderedPort,
            WebpageTypeCatalogPort webpageTypes,
            @Value("${neis.address.preflight.shell-sample-bytes:200000}") int shellSampleBytes) {
        this.scrapePort = scrapePort;
        this.renderedPort = renderedPort;
        this.webpageTypes = webpageTypes;
        this.shellSampleBytes = shellSampleBytes;
    }

    @Override
    public Outcome preflight(Address address) {
        Map<String, Object> evidence = new LinkedHashMap<>();
        // Static fetch first: cheap, and enough for server-rendered
        // pages. The scrape connectors follow redirects and fail loud
        // on non-2xx — that failure IS the preflight outcome for an
        // unreachable address.
        ScrapeResult staticFetch = scrapePort.scrape(address);
        String sample = staticFetch.html();
        if (sample.length() > shellSampleBytes) {
            sample = sample.substring(0, shellSampleBytes);
        }
        evidence.put("httpStatus", 200);
        evidence.put("contentType", staticFetch.contentType());
        evidence.put("sizeBytes", staticFetch.sizeBytes());
        String shellMarker = shellIndicator(sample);
        evidence.put("shellIndicator", shellMarker);

        boolean renderedProbeUsed = false;
        if (shellMarker != null && renderedPort.getIfAvailable() != null) {
            // The static fetch yielded an application shell: a rendered
            // probe sees the page the user's browser sees — its
            // classification is the better estimate.
            try {
                ScrapeResult rendered = renderedPort.getIfAvailable().scrape(address);
                evidence.put("renderedSizeBytes", rendered.sizeBytes());
                sample = rendered.html().length() > shellSampleBytes
                        ? rendered.html().substring(0, shellSampleBytes)
                        : rendered.html();
                renderedProbeUsed = true;
            } catch (RuntimeException e) {
                evidence.put("renderedProbeError", String.valueOf(e.getMessage()));
                log.warn("[PREFLIGHT] {}: rendered probe failed: {}",
                        address.id(), e.getMessage());
            }
        }
        evidence.put("renderedProbe", renderedProbeUsed);

        // Classification: the deterministic heuristics (US-06.5)
        // decide; they are reproducible and explainable. A previous
        // classification is never wiped by a failed probe (the caller
        // persists only on a usable outcome).
        String classified = new WebpageTypeClassifier()
                .classify(staticFetch.contentType(), sample);
        evidence.put("webpageType", classified);

        // Connector estimate: from the type's strategy in the taxonomy
        // (http -> Apache HttpClient 5; rendered -> Playwright; feed ->
        // the feed parser; unknown -> the per-run override decides).
        String estimatedConnector = switch (classified == null ? ""
                : webpageTypes.byId(classified).map(WebpageType::strategy).orElse("")) {
            case WebpageType.STRATEGY_RENDERED -> "rendered";
            case WebpageType.STRATEGY_FEED -> "feed";
            case WebpageType.STRATEGY_HTTP -> "http";
            default -> null;
        };
        evidence.put("estimatedConnector", estimatedConnector);
        String evidenceText = "static fetch " + staticFetch.sizeBytes() + " bytes"
                + (shellMarker == null ? "" : ", shell indicator: " + shellMarker)
                + (renderedProbeUsed ? ", rendered probe used" : "")
                + ", classified " + (classified == null ? "<unclassified>" : classified)
                + (estimatedConnector == null ? ""
                : ", connector estimate: " + estimatedConnector);
        log.info("[PREFLIGHT] {}: {}", address.id(), evidenceText);
        return new Outcome(classified, estimatedConnector,
                json(evidence), evidenceText);
    }

    /** The first shell DOM marker found, or null for a content page. */
    private static String shellIndicator(String sample) {
        String lower = sample.toLowerCase(java.util.Locale.ROOT);
        for (String marker : SHELL_MARKERS) {
            if (lower.contains(marker)) {
                return marker;
            }
        }
        return null;
    }

    /** Flat JSON object of the evidence map (values are scalars/strings). */
    private static String json(Map<String, Object> evidence) {
        StringBuilder json = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> e : evidence.entrySet()) {
            if (!first) {
                json.append(',');
            }
            first = false;
            json.append('"').append(e.getKey()).append("\":");
            Object value = e.getValue();
            if (value == null) {
                json.append("null");
            } else if (value instanceof Number || value instanceof Boolean) {
                json.append(value);
            } else {
                json.append('"').append(value.toString()
                        .replace("\\", "\\\\").replace("\"", "\\\""))
                        .append('"');
            }
        }
        return json.append('}').toString();
    }
}
