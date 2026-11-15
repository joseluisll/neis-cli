package es.jllopez.neis.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Preconfigured WEBSCRAPE filters (US-06.6, issue #73): filter
 * name/value pairs defined in configuration (application.yml), per
 * address, applied BEFORE extraction — URL query parameters for
 * static pages, operated filter controls for rendered pages. The
 * per-run override takes precedence (the {@code filter.<name>}
 * WEBSCRAPE parameters, the same override semantics as the selector
 * overrides of US-12.6); a run without overrides uses the
 * preconfigured set of the address; neither present, no filtering.
 *
 * <pre>
 * neis:
 *   filters:
 *     preconfigured:
 *       - address: ADDR-006
 *         name: programme
 *         value: EU4Health
 * </pre>
 *
 * <p>Configuration-time validation: a blank name or value fails the
 * boot, so an unusable filter never reaches a task; a duplicated
 * filter name for one address fails the boot too (the same rule as
 * the duplicate per-run parameter refusal of US-12.3).</p>
 */
@ConfigurationProperties(prefix = "neis.filters")
public record PreconfiguredFilterProperties(List<Entry> preconfigured) {

    public PreconfiguredFilterProperties {
        preconfigured = preconfigured == null ? List.of() : List.copyOf(preconfigured);
        for (Entry entry : preconfigured) {
            if (entry.address() == null || entry.address().isBlank()) {
                throw new IllegalArgumentException("A preconfigured filter needs an "
                        + "address id — entry '" + entry.name()
                        + "' carries none (US-06.6 review finding: a blank id "
                        + "would boot fine and silently never apply)");
            }
            if (entry.name() == null || entry.name().isBlank()) {
                throw new IllegalArgumentException(
                        "A preconfigured filter needs a name (address "
                                + entry.address() + ")");
            }
            if (entry.value() == null || entry.value().isBlank()) {
                throw new IllegalArgumentException("The preconfigured filter '"
                        + entry.name() + "' (address " + entry.address()
                        + ") needs a value");
            }
        }
        long named = preconfigured.stream()
                .map(e -> e.address() + "\u0000" + e.name().toLowerCase(java.util.Locale.ROOT))
                .distinct().count();
        if (named != preconfigured.size()) {
            throw new IllegalArgumentException(
                    "Duplicate preconfigured filter name for one address — refused "
                            + "(US-06.6; the same rule as the duplicate per-run "
                            + "parameter refusal of US-12.3)");
        }
    }

    /** One preconfigured filter entry: name=value for one address. */
    public record Entry(String address, String name, String value) {
    }
}
