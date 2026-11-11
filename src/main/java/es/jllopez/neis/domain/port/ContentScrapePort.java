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
}
