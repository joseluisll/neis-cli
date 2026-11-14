package es.jllopez.neis.domain.model;

import java.time.Instant;

/**
 * One sighting of a stored news/event item (US-14.5, issue #83): the
 * record of a capture in which the item appeared. Re-encountering a
 * known item — in a later capture, on another page, from another
 * address — updates the existing record and adds a sighting instead of
 * creating a new record. The sighting carries the US-14.2 provenance
 * of that sighting (the extracting execution and the scrape.html
 * content record), so the item's appearance history is traceable.
 */
public record NewsEventSighting(
        String itemId,
        String addressId,
        String contentId,
        String executionId,
        Instant seenAt) {
}
