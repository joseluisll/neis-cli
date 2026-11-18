package es.jllopez.neis.domain.port;

/**
 * Port for the address preflight capture (US-13.6, issue #65): a
 * lightweight entry-page probe of a registered address that records
 * HTTP status, content type, size and shell indicators (a rendered
 * probe when the static fetch yields an application shell), classifies
 * the address into the webpage-type taxonomy (US-06.5) and estimates
 * the retrieval connector for the executor (HttpClient 5 / Playwright
 * / fixtures behind the fetching port). Infrastructure provides the
 * connector; the AI (or deterministic heuristics as fallback) performs
 * the estimation through the provider-agnostic integration point
 * (US-13.1).
 *
 * <p>The probe's full evidence is a JSON object string (the capture
 * metadata column {@code captures.preflight}); the caller-facing
 * summary is the record — the classification, the estimated connector
 * (a suggestion the user can override) and the evidence.</p>
 */
public interface AddressPreflightPort {

    /** The outcome of one preflight probe of an address. */
    record Outcome(String webpageType, String estimatedConnector,
                   String preflightJson, String evidence) {
    }

    /**
     * Probes the address's entry page and classifies/estimates.
     *
     * @throws IllegalStateException when the probe cannot reach the
     *                               address at all (the caller reports
     *                               and leaves any stored state alone)
     */
    Outcome preflight(es.jllopez.neis.domain.model.Address address);
}
