package es.jllopez.neis.domain.model;

/**
 * Result of the SCRAPE step: the HTML5 content obtained for an address
 * (requirement US-06.1, issue #27).
 */
public record ScrapeResult(String html, String contentType, int sizeBytes) {
}
