package es.jllopez.neis.domain.model;

/**
 * Result of the SCRAPE step: the HTML5 content obtained for an address
 * (requirement US-06.1, issue #27).
 *
 * <p>US-06.14 (issue #138): {@code sourceNote} carries the provenance
 * of a served page when it did NOT come from a fresh network fetch —
 * the cached connector sets it to {@code "cached; original scrape
 * <timestamp> (<contentId>)"} so the run's outcome, logs and reports
 * can honestly mark the reuse. Null for every fresh fetch (static
 * fixtures, HTTP, rendered); the single-task and SSAFP outcomes append
 * it when present.</p>
 */
public record ScrapeResult(String html, String contentType, int sizeBytes,
                           String sourceNote) {

    /** The fresh-fetch shape: no provenance note (US-06.14). */
    public ScrapeResult(String html, String contentType, int sizeBytes) {
        this(html, contentType, sizeBytes, null);
    }
}
