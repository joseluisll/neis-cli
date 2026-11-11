package es.jllopez.neis.domain.model;

/**
 * Result of the SIMPLIFY step: cleansed HTML5 reduced to action and data
 * elements, capped at 100 KB (requirements US-07.1..US-07.3, issues
 * #30-#32).
 */
public record SimplifiedContent(String html, int sizeBytes) {
}
