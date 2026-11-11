package es.jllopez.neis.domain.model;

import java.util.List;

/**
 * One filter field offered by an address listing, extracted by the
 * FILTER block (US-17): the field name and the values it offers for
 * narrowing the listing.
 *
 * @param name   human-readable name of the filter field
 * @param values values the filter offers (empty for free-text inputs)
 */
public record FilterDefinition(String name, List<String> values) {
}
