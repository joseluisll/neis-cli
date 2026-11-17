package es.jllopez.neis.application;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Catalog of the page-specific obstructing selectors (US-06.7, issue
 * #75): serves the CSS selectors configured for an address and listing
 * kind so the WEBSCRAPE clearing removes the page's own obstructing
 * elements in addition to the generic rules. Lookup follows the
 * preconfigured-selector semantics (US-12.1): a specific (address +
 * kind) entry wins over a kind-wide default; entries for another kind
 * never match; ALL matching entries apply (a page may declare several
 * obstructing pieces).
 *
 * <p>Configuration-time validation (US-12.3, issue #48 rule): every
 * selector must parse against the extraction engine — an invalid one
 * fails the boot, so an invalid selector never reaches a run.</p>
 */
@Component
public class ClearingSelectors {

    private final ClearingProperties properties;

    public ClearingSelectors(ClearingProperties properties,
                             es.jllopez.neis.domain.port.SelectorExtractionPort extractionPort) {
        for (ClearingProperties.Entry entry : properties.selectors()) {
            if (entry.selector() != null && !entry.selector().isBlank()
                    && !extractionPort.isSyntaxValid(entry.selector())) {
                throw new IllegalArgumentException(
                        "Invalid clearing selector for " + entry.address()
                                + "/" + entry.kind() + ": '" + entry.selector()
                                + "' does not parse (US-12.3)");
            }
        }
        this.properties = properties;
    }

    /**
     * The clearing selectors configured for this address and kind, in
     * configuration order: every specific (address + kind) entry and
     * every kind-wide default applies. Empty when none is configured.
     */
    public List<String> find(String addressId, String kind) {
        List<String> selectors = new ArrayList<>();
        if (addressId == null || kind == null) {
            return selectors;
        }
        for (ClearingProperties.Entry entry : properties.selectors()) {
            if (entry.selector() == null || entry.selector().isBlank()) {
                continue;
            }
            boolean kindMatches = kind.equals(entry.kind());
            boolean specific = addressId.equals(entry.address());
            boolean kindWide = entry.address() == null || entry.address().isBlank();
            if (kindMatches && (specific || kindWide)) {
                selectors.add(entry.selector());
            }
        }
        return selectors;
    }

    /** All configured entries (boot-check and management views). */
    public List<ClearingProperties.Entry> all() {
        return properties.selectors();
    }

    /** Optional single-lookup helper for tests and tooling. */
    public Optional<String> first(String addressId, String kind) {
        List<String> found = find(addressId, kind);
        return found.isEmpty() ? Optional.empty() : Optional.of(found.get(0));
    }
}
