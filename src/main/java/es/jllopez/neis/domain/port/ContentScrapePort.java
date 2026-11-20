package es.jllopez.neis.domain.port;

import es.jllopez.neis.domain.model.Address;
import es.jllopez.neis.domain.model.FilterRequestOption;
import es.jllopez.neis.domain.model.ScrapeResult;

import java.util.List;

/**
 * Port for the SCRAPE step: obtains the HTML5 content of an address
 * (requirement US-06.1, issue #27).
 *
 * <p>Infrastructure provides the connector. The MVP half implementation
 * serves static HTML5 fixtures; the full connector (HttpClient 5, with
 * Playwright for JS-rendered pages) plugs in behind this same port.</p>
 */
public interface ContentScrapePort {

    ScrapeResult scrape(Address address);

    /**
     * Scrapes one page of a paginated listing (US-12.4 iteration slice):
     * page 0 is the base URL, page N the Nth following page. Connectors
     * that cannot address pages throw {@link UnsupportedOperationException};
     * the chain treats that as the end of the pagination.
     */
    default ScrapeResult scrape(Address address, int pageIndex) {
        if (pageIndex == 0) {
            return scrape(address);
        }
        throw new UnsupportedOperationException(
                "This SCRAPE connector cannot address page " + pageIndex
                        + " of " + address.id());
    }

    /**
     * Fetches an arbitrary URL (US-12.4 pagination iteration: the
     * next-page URL discovered from the page's own controls).
     * Connectors that cannot fetch arbitrary URLs throw
     * {@link UnsupportedOperationException}; the iteration reports
     * that and stops.
     */
    default ScrapeResult scrapeUrl(String url) {
        throw new UnsupportedOperationException(
                "This SCRAPE connector cannot fetch arbitrary URLs: " + url);
    }

    /**
     * Scrapes the address with an EXPLICIT retrieval strategy for this
     * run (US-06.5, issue #71): {@code connector} overrides the
     * strategy the address's webpage type would select — the
     * per-run override the requirement shares with the selector
     * overrides (US-12.6). Values are connector-specific; the routers
     * accept their own vocabulary and refuse unknown values loud.
     * The default ignores the override (single-strategy connectors).
     */
    default ScrapeResult scrape(Address address, String connectorOverride) {
        return scrape(address);
    }

    /**
     * Scrapes the listing narrowed by an applied filter criterion
     * (US-06.6 slice): the connector applies the filter BEFORE returning
     * the content — URL query parameters for static pages, operating the
     * page controls for rendered pages. {@code filterRequest} is the
     * AI-derived representation of the criterion to apply (e.g. the
     * query string the site's filter form would produce). Connectors
     * that cannot apply filters throw {@link UnsupportedOperationException};
     * the chain reports that instead of failing the run.
     */
    default ScrapeResult scrapeFiltered(Address address, String filterRequest) {
        throw new UnsupportedOperationException(
                "This SCRAPE connector cannot apply the filter request '" + filterRequest
                        + "' of " + address.id());
    }

    /**
     * Scrapes the listing narrowed by an explicit user filter set
     * (US-06.6, issue #73): the WEBSCRAPE task's configured (or
     * per-run overridden) filters, applied BEFORE extraction — URL
     * query parameters for static pages, operating the page's filter
     * controls (selects, text boxes, tags) for rendered pages.
     * Connectors that cannot apply a given filter fail loud; the
     * caller records the applied set with the capture metadata
     * (US-11.4). An empty map behaves like {@link #scrape(Address)}.
     */
    default ScrapeResult scrapeWithFilters(Address address,
                                           java.util.Map<String, String> filters,
                                           String connectorOverride) {
        if (filters == null || filters.isEmpty()) {
            return scrape(address, connectorOverride);
        }
        throw new UnsupportedOperationException(
                "This SCRAPE connector cannot apply filters " + filters
                        + " of " + address.id() + " (US-06.6)");
    }

    /**
     * The filter requests this connector can currently APPLY for the
     * address (US-06.6): a rendering/scraping connector knows which
     * interactions it can serve. Empty means unconstrained — the
     * connector applies any derived request. The static MVP connector
     * reports the criteria its captured filtered responses embody, so
     * an applied criterion always matches the captured response. The
     * structured form is what lets the FILTER step apply a chain-defined
     * {@code FilterSpec} offline: match the spec against the criteria,
     * take the request token, no AI in the loop.
     */
    default List<FilterRequestOption> supportedFilterRequests(Address address) {
        return List.of();
    }

    /**
     * The fetchability warning for the address under the ACTIVE
     * connector configuration (US-06.12, issue #134): a non-empty
     * value states what a fetch of this address would do or why it
     * would FAIL — naming the mode and the remedy — so registration
     * and verification can surface it before the first fetch. Empty
     * means the address fetches fine as things stand. Connectors
     * without configuration awareness report nothing.
     */
    default java.util.Optional<String> fetchabilityWarning(Address address) {
        return java.util.Optional.empty();
    }
}
