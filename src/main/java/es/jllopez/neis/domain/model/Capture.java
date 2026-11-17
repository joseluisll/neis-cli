package es.jllopez.neis.domain.model;

import java.time.Instant;

/**
 * One webpage capture (US-11.4, issue #69): a fetch/visit of a webpage
 * of a registered address, identified DETERMINISTICALLY — the capture
 * id derives from the webpage URL (never from run timestamps), so the
 * same webpage always maps to the same storage folder and repeated
 * operations land in the same place.
 *
 * <p>{@code filters} (US-06.6, issue #73): the filter set applied
 * BEFORE extraction on this capture (URL parameters for static pages,
 * operated controls for rendered pages), as a JSON object string in
 * config order; null = fetched without filters. A re-capture without
 * filters never wipes a stored set (the same rule as the webpage
 * type).</p>
 *
 * <p>{@code sideTable} (US-07.6, issue #89): the hashed-URL side
 * table of the lean SIMPLIFY tier (placeholder → full URL, JSON); a
 * re-render without hashing never wipes a stored table.</p>
 *
 * <p>{@code cleared} (US-06.7, issue #75): the obstruction-clearing
 * record of the capture, as a JSON object string — the removed
 * element count and the rules that removed at least one element; null
 * = captured without clearing. A re-capture without clearing never
 * wipes a stored record (the same never-wipes rule).</p>
 */
public record Capture(String addressId, String captureId, String url,
                      Instant createdAt, String webpageType, String filters,
                      String cleared, String domDigest, String sideTable) {

    public Capture(String addressId, String captureId, String url, Instant createdAt) {
        this(addressId, captureId, url, createdAt, null, null, null, null, null);
    }

    public Capture(String addressId, String captureId, String url,
                   Instant createdAt, String webpageType) {
        this(addressId, captureId, url, createdAt, webpageType, null, null, null, null);
    }

    public Capture(String addressId, String captureId, String url,
                   Instant createdAt, String webpageType, String filters) {
        this(addressId, captureId, url, createdAt, webpageType, filters, null, null, null);
    }

    public Capture(String addressId, String captureId, String url,
                   Instant createdAt, String webpageType, String filters,
                   String cleared) {
        this(addressId, captureId, url, createdAt, webpageType, filters, cleared, null, null);
    }

    public Capture {
        if (addressId == null || addressId.isBlank()) {
            throw new IllegalArgumentException("A capture needs an address id");
        }
        if (captureId == null || captureId.isBlank()) {
            throw new IllegalArgumentException("A capture needs a capture id");
        }
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("A capture needs the webpage URL");
        }
    }
}
