package es.jllopez.neis.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Configuration binding of the page-specific obstructing selectors
 * (US-06.7, issue #75): {@code neis.scrape.clearing.selectors} declares
 * the CSS selectors of the elements a WEBSCRAPE run removes from the
 * captured HTML5 (popups, banners, ads the generic rules miss), per
 * address or kind-wide — the selector model (US-12) applied to
 * obstruction clearing.
 *
 * <pre>
 * neis:
 *   scrape:
 *     clearing:
 *       selectors:
 *         - address: ADDR-005     # specific to one address
 *           kind: news
 *           selector: "#site-overlay"
 *         - kind: news            # kind-wide default (no address)
 *           selector: ".promo-popup"
 * </pre>
 *
 * <p>Configuration-time validation (US-12.3, the same rule as the
 * preconfigured item selectors): every selector must parse — an
 * invalid one fails the boot, so a bad selector never reaches a run.
 * A selector that parses but matches nothing on a page simply removes
 * nothing (clearing is best-effort complements to the generic rules,
 * not extraction).</p>
 */
@ConfigurationProperties(prefix = "neis.scrape.clearing")
public record ClearingProperties(List<Entry> selectors) {

    public ClearingProperties {
        selectors = selectors == null ? List.of() : List.copyOf(selectors);
    }

    /** One configured obstructing selector entry. */
    public record Entry(String address, String kind, String selector) {
    }
}
