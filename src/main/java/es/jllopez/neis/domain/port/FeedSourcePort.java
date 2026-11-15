package es.jllopez.neis.domain.port;

import es.jllopez.neis.domain.model.Address;
import es.jllopez.neis.domain.model.NewsEventItem;

import java.util.List;

/**
 * Port for RSS/Atom feed sources (US-06.8, issue #86): a feed address
 * is fetched as a document and its structured items are transformed
 * into the standard news/event records — no HTML scraping when the
 * feed suffices. The parsing uses the proven open-source JVM library
 * the requirement names (Rome), behind this same port.
 */
public interface FeedSourcePort {

    /**
     * Fetches the raw feed document of a feed address with the same
     * fetch discipline as the HTTP scrape connector (timeout, size
     * cap, watchdog on the body read). The returned text is the XML
     * document, recorded as an artifact and the items' provenance.
     */
    String fetch(Address address);

    /**
     * Parses a feed document into standard news/event items, newest
     * first as the feed orders them, capped at {@code maxItems}.
     * Rome normalizes RSS 2.0 and Atom (and other formats it
     * detects) into one entry model; the mapping is deterministic:
     * title, description (or content), link and the published date
     * (ISO-8601) when the feed carries one — an entry without a
     * title/link is still mapped (the store flags its completeness,
     * US-14.4), never silently dropped.
     */
    List<NewsEventItem> parse(String feedXml, String addressId,
                              String itemKind, int maxItems, String baseUrl);
}
