package es.jllopez.neis.domain.model;

/**
 * One news or event item discovered by selector-based extraction
 * (requirements US-12.2 / US-13.2).
 */
public record NewsEventItem(
        String addressId,
        String kind,
        String title,
        String url,
        String date) {
}
