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
     * cap, watchdog on the body read). The returned bytes are the
     * XML document, recorded as an artifact and the items'
     * provenance — the ENCODING is left to the parse: an XML
     * document carries its own charset declaration, and decoding
     * here (e.g. unconditionally as UTF-8) would silently corrupt
     * any non-UTF-8 feed.
     */
    byte[] fetch(Address address);

    /**
     * Decodes the fetched document for artifact storage and display,
     * honoring the XML declaration's charset (BOM/declaration
     * detection, UTF-8 fallback).
     */
    String toDocumentText(byte[] feedBytes);

    /**
     * Parses a feed document into standard news/event items, newest
     * first as the feed orders them, capped at {@code maxItems}.
     * Rome normalizes RSS 2.0 and Atom (and other formats it
     * detects) into one entry model; the mapping is deterministic:
     * title, description (or content), link and the published date
     * (ISO-8601) when the feed carries one (the updated date only
     * as the published date's fallback) — an entry without a
     * title/link is still mapped (the store flags its completeness,
     * US-14.4), never silently dropped.
     */
    List<NewsEventItem> parse(byte[] feedXml, String addressId,
                              String itemKind, int maxItems, String baseUrl);
}
