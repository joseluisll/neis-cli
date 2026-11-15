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
 */
public record Capture(String addressId, String captureId, String url,
                      Instant createdAt, String webpageType, String filters) {

    public Capture(String addressId, String captureId, String url, Instant createdAt) {
        this(addressId, captureId, url, createdAt, null, null);
    }

    public Capture(String addressId, String captureId, String url,
                   Instant createdAt, String webpageType) {
        this(addressId, captureId, url, createdAt, webpageType, null);
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
