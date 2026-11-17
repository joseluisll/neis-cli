package es.jllopez.neis.domain.port;

import es.jllopez.neis.domain.model.ElementCatalog;

/**
 * Port for the DOM analysis of a captured page (US-06.9, issue #88):
 * walks the captured HTML5 and produces the pruned element tree with
 * incremental ids, the element catalog and the change digest —
 * Skyvern's scraper concepts re-implemented in Java on top of jsoup
 * (static pages); the rendered connector hands the rendered DOM over
 * through the same port (no Skyvern code is copied, AGPL respected).
 *
 * <p>Infrastructure provides the connector. The catalog is what the
 * selector-based extraction (US-12.2) and the AI selector
 * identification (US-13.3/US-13.4) work on: every relevant element
 * carries a stable id and a CSS path, so an analysis answer can point
 * at elements unambiguously.</p>
 */
public interface DomAnalysisPort {

    /**
     * Analyzes the captured HTML5 into the pruned element tree.
     *
     * @param html the captured HTML5 (already cleared, US-06.7)
     * @return the element catalog: the numbered tree, the change
     *         digest and the placeholder map of hashed long URLs
     */
    ElementCatalog analyze(String html);
}
