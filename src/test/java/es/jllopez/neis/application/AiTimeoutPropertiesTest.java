package es.jllopez.neis.application;

import es.jllopez.neis.domain.model.AiOperation;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * US-13.8 (issue #132): the per-operation AI timeout configuration —
 * the 240-second default, per-operation overrides, positive-value
 * validation. Flat fields (the live finding: map keys do not bind
 * from environment variables, which is how the installed service is
 * configured).
 */
class AiTimeoutPropertiesTest {

    @Test
    void emptyConfigurationYieldsThe240SecondDefault() {
        AiTimeoutProperties properties = new AiTimeoutProperties(
                null, null, null, null, null, null, null, null,
                null, null, null, null);

        assertEquals(Duration.ofSeconds(240),
                properties.forOperation(AiOperation.CLASSIFICATION));
        assertEquals(Duration.ofSeconds(240),
                properties.forOperation(AiOperation.GENERIC));
        assertEquals(Duration.ofSeconds(240), properties.maxTimeout());
    }

    @Test
    void theDefaultValueAppliesToEveryOperationWithoutAnOverride() {
        AiTimeoutProperties properties = new AiTimeoutProperties(
                Duration.ofSeconds(30), null, null, null, null, null,
                null, null, null, null, null, null);

        assertEquals(Duration.ofSeconds(30),
                properties.forOperation(AiOperation.CLASSIFICATION));
        assertEquals(Duration.ofSeconds(30),
                properties.forOperation(AiOperation.PREFLIGHT));
        assertEquals(Duration.ofSeconds(30), properties.maxTimeout());
    }

    @Test
    void perOperationOverridesWinOverTheDefault() {
        AiTimeoutProperties properties = new AiTimeoutProperties(
                Duration.ofSeconds(30),   // default
                null,                     // generic
                Duration.ofSeconds(45),   // classification
                Duration.ofSeconds(180),  // selector-news
                null, null, null, null, null, null, null, null);

        assertEquals(Duration.ofSeconds(180),
                properties.forOperation(AiOperation.SELECTOR_NEWS));
        assertEquals(Duration.ofSeconds(45),
                properties.forOperation(AiOperation.CLASSIFICATION));
        assertEquals(Duration.ofSeconds(30),
                properties.forOperation(AiOperation.FILTER_APPLY));
        // the client backstop must not cut short the longest operation
        assertEquals(Duration.ofSeconds(180), properties.maxTimeout());
    }

    @Test
    void nonPositiveValuesFailTheConstruction() {
        assertThrows(IllegalArgumentException.class,
                () -> new AiTimeoutProperties(Duration.ZERO, null, null,
                        null, null, null, null, null, null, null, null,
                        null));
        assertThrows(IllegalArgumentException.class,
                () -> new AiTimeoutProperties(null, null,
                        Duration.ofSeconds(-5), null, null, null, null,
                        null, null, null, null, null));
    }
}
