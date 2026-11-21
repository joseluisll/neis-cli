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

    /**
     * The CHECKBOX/RADIO facet groups of the captured page (US-06.19,
     * issue #150) — the filter-panel shape of client-rendered listings,
     * which the regex parser of the exposed-filter detector cannot
     * group: the GROUP name is the facet section's heading (the nearest
     * preceding container heading in the DOM walk), the values are the
     * controls' labels. Page-agnostic DOM traversal, no site knowledge.
     * DOM parsing is infrastructure work (jsoup), hence here and not on
     * the domain detector.
     *
     * @param html the RAW captured HTML5 (pre-clearing: facet panels
     *             often live inside overlays the clearing rules remove)
     * @return one FilterDefinition per facet group, document order
     */
    java.util.List<es.jllopez.neis.domain.model.FilterDefinition>
            checkboxFacetGroups(String html);
}
