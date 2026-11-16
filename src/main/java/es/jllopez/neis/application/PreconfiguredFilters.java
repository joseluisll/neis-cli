package es.jllopez.neis.application;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Catalog of the preconfigured WEBSCRAPE filters (US-06.6, issue #73):
 * serves the filter set configured for an address so the WEBSCRAPE
 * task applies it BEFORE extraction, deterministically — URL query
 * parameters for static pages, operated filter controls for rendered
 * pages. The per-run override (the {@code filter.<name>} task
 * parameters) takes precedence over this configuration, the same
 * override semantics as the selector overrides (US-12.6).
 */
@Component
public class PreconfiguredFilters {

    private final PreconfiguredFilterProperties properties;

    public PreconfiguredFilters(PreconfiguredFilterProperties properties) {
        this.properties = properties;
    }

    /**
     * The preconfigured filter set for this address, in configuration
     * order; empty when none is configured. Duplicates are refused at
     * configuration time, so the map is well-formed by construction.
     * The id matches CASE-INSENSITIVELY — the catalog's documented id
     * semantics (US-06.10 review blocking finding: an exact-equals
     * match let a case-variant configured id pass the boot check yet
     * never apply — the dead-entry failure mode this class exists to
     * prevent).
     */
    public Optional<Map<String, String>> find(String addressId) {
        if (addressId == null || addressId.isBlank()) {
            return Optional.empty();
        }
        Map<String, String> filters = new LinkedHashMap<>();
        for (PreconfiguredFilterProperties.Entry entry : properties.preconfigured()) {
            if (addressId.equalsIgnoreCase(entry.address())) {
                filters.put(entry.name(), entry.value());
            }
        }
        return filters.isEmpty() ? Optional.empty() : Optional.of(filters);
    }
}
