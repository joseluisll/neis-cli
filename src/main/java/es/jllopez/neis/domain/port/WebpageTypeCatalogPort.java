package es.jllopez.neis.domain.port;

import es.jllopez.neis.domain.model.WebpageType;

import java.util.List;
import java.util.Optional;

/**
 * Port for the webpage-type taxonomy (US-06.5, issue #71): resolves
 * a webpage type id to its retrieval strategy. The taxonomy is
 * configuration-driven — the infrastructure connector supplies the
 * built-in types plus whatever types the configuration adds, so a new
 * type never requires a domain change. Unknown ids resolve to empty;
 * callers decide whether that is a hard failure (routing) or a soft
 * fallback (classification).
 */
public interface WebpageTypeCatalogPort {

    /** The type with this id, or empty when the taxonomy does not know it. */
    Optional<WebpageType> byId(String id);

    /** Every known type (built-ins plus configured extensions). */
    List<WebpageType> all();

    /** Whether the taxonomy knows this id (null/blank is never known). */
    default boolean isKnown(String id) {
        return id != null && !id.isBlank() && byId(id.trim()).isPresent();
    }
}
