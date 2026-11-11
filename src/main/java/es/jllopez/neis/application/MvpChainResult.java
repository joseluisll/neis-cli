package es.jllopez.neis.application;

import es.jllopez.neis.domain.model.Address;
import es.jllopez.neis.domain.model.AddressCapability;
import es.jllopez.neis.domain.model.FilterDefinition;
import es.jllopez.neis.domain.model.NewsEventItem;
import es.jllopez.neis.domain.model.PageAnalysis;
import es.jllopez.neis.domain.model.PaginationInfo;

import java.util.List;
import java.util.Set;

/**
 * Outcome of one MVP chain run (SCRAPE - SIMPLIFY - ANALYZE - FILTER - PRINT)
 * for a single address. The PRINT step renders this for the shell.
 *
 * @param selectorSource provenance of the used selector: "stored"
 *                        (persisted from a previous AI run — AI skipped),
 *                        "ai" (identified and validated this run, now
 *                        persisted) or "fallback" (static heuristic)
 * @param capabilities    listing capabilities ANALYZE detected and FLAGGED
 *                        onto the address (US-17)
 * @param pageUrlPattern  AI-derived page URL template ({n} = page number),
 *                        only when the address is flagged PAGINATION
 * @param pagesScraped    pages the pagination-iteration block scraped
 * @param correctedAddress AI-proposed correction of the registered
 *                        address (US-13.7), applied for this run; null
 *                        when the registered address needed no correction
 * @param filterZoneSelector CSS selector of the filter zone identified
 *                        by the FILTER block (US-17.1), validated and
 *                        persisted with the address; null when not flagged
 * @param filters         filter definitions extracted by the FILTER block
 * @param filterSource    provenance of the filter definitions: "ai",
 *                        "fallback" or "skipped" (address not flagged
 *                        with the filtering capability)
 * @param appliedFilterField AI-chosen filter field APPLIED to narrow the
 *                        listing before extraction (US-06.6 slice); null
 *                        when no criterion was applied
 * @param appliedFilterValue value of the applied filter criterion
 * @param filterRequest   request representation the connector applied
 *                        (query parameters / control token)
 * @param filteredItems   items extracted from the filtered response
 * @param filteredScrapeArtifact path of the filtered scrape artifact
 * @param filterApplicationNote why no criterion was applied (skipped /
 *                        connector cannot apply / AI chose none)
 */
public record MvpChainResult(
        Address address,
        String scrapeArtifact,
        String simplifyArtifact,
        int scrapeSizeBytes,
        int simplifiedSizeBytes,
        PaginationInfo pagination,
        PageAnalysis analysis,
        String selectedSelector,
        String selectorSource,
        List<NewsEventItem> items,
        Set<AddressCapability> capabilities,
        String capabilityEvidence,
        String pageUrlPattern,
        int pagesScraped,
        String correctedAddress,
        String filterZoneSelector,
        List<FilterDefinition> filters,
        String filterSource,
        String filterNote,
        String appliedFilterField,
        String appliedFilterValue,
        String filterRequest,
        List<NewsEventItem> filteredItems,
        String filteredScrapeArtifact,
        String filterApplicationNote) {
}
