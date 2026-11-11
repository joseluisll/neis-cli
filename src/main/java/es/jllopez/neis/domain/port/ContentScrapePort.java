package es.jllopez.neis.domain.port;

import es.jllopez.neis.domain.model.Address;
import es.jllopez.neis.domain.model.ScrapeResult;

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
}
