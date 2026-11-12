package es.jllopez.neis.domain.model;

/**
 * One filter request a SCRAPE connector can currently APPLY for an
 * address (US-06.6): what the criterion means in the source's own
 * terms and the concrete request token the connector serves. The
 * structured form (replacing free-text strings) is what lets the
 * FILTER step apply a chain-defined {@link FilterSpec} offline: the
 * spec is matched against the criteria and the request token goes to
 * {@code scrapeFiltered} — no AI in the loop.
 *
 * @param criterion human-readable description of the criterion
 *                  ("Programme = EU4Health (HORIZON)")
 * @param request   the request representation the connector applies
 *                  ("programme=EU4Health")
 */
public record FilterRequestOption(String criterion, String request) {
}
