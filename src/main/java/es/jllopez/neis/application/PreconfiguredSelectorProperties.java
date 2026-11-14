package es.jllopez.neis.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Preconfigured selectors (US-12.1, issue #46): CSS selectors defined
 * in configuration (application.yml), per address and listing kind, so
 * the extraction tasks work deterministically — no AI identification
 * needed for the addresses the owner already knows. Selectors are
 * stored as strings (owner technology choice, 2026-11-11: CSS as
 * supported by jsoup).
 *
 * <pre>
 * neis:
 *   selectors:
 *     preconfigured:
 *       - address: ADDR-005     # specific to one address
 *         kind: event
 *         selector: "div.view-content > article"
 *       - kind: news            # kind-wide default (no address)
 *         selector: "ul.news-list li"
 * </pre>
 *
 * <p>A specific (address + kind) entry wins over a kind-wide default.
 * A validated preconfigured selector is persisted through the selector
 * store with {@code discoveredBy=preconfigured}, so later runs take the
 * stored fast path; the configuration stays the source of record.</p>
 */
@ConfigurationProperties(prefix = "neis.selectors")
public record PreconfiguredSelectorProperties(List<Entry> preconfigured) {

    public PreconfiguredSelectorProperties {
        preconfigured = preconfigured == null ? List.of() : List.copyOf(preconfigured);
    }

    /** One preconfigured selector entry. */
    public record Entry(String address, String kind, String selector) {
    }
}
