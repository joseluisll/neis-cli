package es.jllopez.neis.application;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The preconfigured-filter catalog BOOTS FROM CONFIGURATION (US-06.6
 * review finding: no test exercised the yaml -> properties -> catalog
 * path end to end). The ApplicationContextRunner binds
 * {@code neis.filters.preconfigured.*} through the REAL Spring binder,
 * so the served filter set and the configuration-time validation rules
 * (blank name/value/address, duplicate names) are the production ones.
 */
class PreconfiguredFiltersBindingTest {

    @Configuration
    @EnableConfigurationProperties(PreconfiguredFilterProperties.class)
    static class PropertiesConfig {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesConfig.class, PreconfiguredFilters.class);

    @Test
    void filtersBindFromConfigurationAndAreServedPerAddress() {
        runner.withPropertyValues(
                        "neis.filters.preconfigured[0].address=ADDR-006",
                        "neis.filters.preconfigured[0].name=programme",
                        "neis.filters.preconfigured[0].value=EU4Health",
                        "neis.filters.preconfigured[1].address=ADDR-003",
                        "neis.filters.preconfigured[1].name=lang",
                        "neis.filters.preconfigured[1].value=en")
                .run(context -> {
                    PreconfiguredFilters filters =
                            context.getBean(PreconfiguredFilters.class);
                    Map<String, String> expected = new LinkedHashMap<>();
                    expected.put("programme", "EU4Health");
                    assertEquals(expected, filters.find("ADDR-006").orElseThrow());
                    assertEquals(Map.of("lang", "en"), filters.find("ADDR-003").orElseThrow());
                    assertTrue(filters.find("ADDR-001").isEmpty(),
                            "an address with no entry is served nothing");
                });
    }

    @Test
    void anAddresslessEntryFailsTheBoot() {
        // US-06.6 addendum review finding: a blank address id used to
        // boot fine and silently never apply — refused loud now.
        runner.withPropertyValues(
                        "neis.filters.preconfigured[0].address=",
                        "neis.filters.preconfigured[0].name=programme",
                        "neis.filters.preconfigured[0].value=EU4Health")
                .run(context -> {
                    assertTrue(hasCause(context.getStartupFailure(),
                                    IllegalArgumentException.class),
                            "startup failure was: " + context.getStartupFailure());
                });
    }

    @Test
    void caseDifferingDuplicateNamesFailTheBoot() {
        runner.withPropertyValues(
                        "neis.filters.preconfigured[0].address=ADDR-006",
                        "neis.filters.preconfigured[0].name=programme",
                        "neis.filters.preconfigured[0].value=EU4Health",
                        "neis.filters.preconfigured[1].address=ADDR-006",
                        "neis.filters.preconfigured[1].name=PROGRAMME",
                        "neis.filters.preconfigured[1].value=Horizon")
                .run(context -> assertTrue(hasCause(context.getStartupFailure(),
                                IllegalArgumentException.class),
                        "startup failure was: " + context.getStartupFailure()));
    }

    /** The binder wraps configuration-time validation failures. */
    private static boolean hasCause(Throwable failure,
                                    Class<? extends Throwable> type) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (type.isInstance(t)) {
                return true;
            }
        }
        return false;
    }
}
