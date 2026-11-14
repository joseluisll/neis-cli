package es.jllopez.neis.application;

import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Catalog of the preconfigured selectors (US-12.1, issue #46): serves
 * the CSS selector configured for an address and listing kind, so the
 * ANALYZE selector strategy can use it deterministically — before the
 * AI is ever asked (the extraction engine itself is US-12.2).
 *
 * <p>Lookup order: a specific entry (address + kind) wins over a
 * kind-wide default (an entry with no address); entries for another
 * kind never match.</p>
 */
@Component
public class PreconfiguredSelectors {

    private final PreconfiguredSelectorProperties properties;

    public PreconfiguredSelectors(PreconfiguredSelectorProperties properties) {
        this.properties = properties;
    }

    /** The preconfigured CSS selector for this address and kind, if any. */
    public Optional<String> find(String addressId, String kind) {
        if (addressId == null || kind == null) {
            return Optional.empty();
        }
        return properties.preconfigured().stream()
                .filter(e -> kind.equals(e.kind()) && e.selector() != null
                        && !e.selector().isBlank())
                .filter(e -> addressId.equals(e.address()))
                .map(PreconfiguredSelectorProperties.Entry::selector)
                .findFirst()
                .or(() -> properties.preconfigured().stream()
                        .filter(e -> kind.equals(e.kind()) && e.selector() != null
                                && !e.selector().isBlank())
                        .filter(e -> e.address() == null || e.address().isBlank())
                        .map(PreconfiguredSelectorProperties.Entry::selector)
                        .findFirst());
    }
}
