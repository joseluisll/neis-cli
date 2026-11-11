package es.jllopez.neis.domain.model;

/**
 * One news or event item discovered by selector-based extraction
 * (requirements US-12.2 / US-06.4 — extracted item data: title,
 * description, date and the detail URL that opens the item itself).
 */
public record NewsEventItem(
        String addressId,
        String kind,
        String title,
        String description,
        String url,
        String date) {
}
