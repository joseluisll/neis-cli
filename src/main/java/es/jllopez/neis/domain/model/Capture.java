package es.jllopez.neis.domain.model;

import java.time.Instant;

/**
 * One webpage capture (US-11.4, issue #69): a fetch/visit of a webpage
 * of a registered address, identified DETERMINISTICALLY — the capture
 * id derives from the webpage URL (never from run timestamps), so the
 * same webpage always maps to the same storage folder and repeated
 * operations land in the same place.
 */
public record Capture(String addressId, String captureId, String url, Instant createdAt) {

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
