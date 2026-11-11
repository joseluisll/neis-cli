package es.jllopez.neis.application;

import es.jllopez.neis.domain.model.Address;
import es.jllopez.neis.domain.model.NewsEventItem;
import es.jllopez.neis.domain.model.PageAnalysis;
import es.jllopez.neis.domain.model.PaginationInfo;

import java.util.List;

/**
 * Outcome of one MVP chain run (SCRAPE - SIMPLIFY - ANALYZE - PRINT) for
 * a single address. The PRINT step renders this for the shell.
 *
 * @param selectorSource provenance of the used selector: "stored"
 *                        (persisted from a previous AI run — AI skipped),
 *                        "ai" (identified and validated this run, now
 *                        persisted) or "fallback" (static heuristic)
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
        List<NewsEventItem> items) {
}
