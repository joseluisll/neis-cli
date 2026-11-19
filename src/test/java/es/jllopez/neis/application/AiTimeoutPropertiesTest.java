package es.jllopez.neis.application;

import es.jllopez.neis.domain.model.AiOperation;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * US-13.8 (issue #132): the per-operation AI timeout configuration —
 * the 240-second default, per-operation overrides, and the
 * boot-failing validation (unknown key = typo, non-positive value).
 */
class AiTimeoutPropertiesTest {

    @Test
    void emptyConfigurationYieldsThe240SecondDefault() {
        AiTimeoutProperties properties = new AiTimeoutProperties(Map.of());

        assertEquals(Duration.ofSeconds(240), properties.forOperation(AiOperation.CLASSIFICATION));
        assertEquals(Duration.ofSeconds(240), properties.forOperation(AiOperation.GENERIC));
        assertEquals(Duration.ofSeconds(240), properties.maxTimeout());
    }

    @Test
    void theDefaultKeyAppliesToEveryOperationWithoutAnOverride() {
        AiTimeoutProperties properties = new AiTimeoutProperties(
                Map.of("default", Duration.ofSeconds(30)));

        assertEquals(Duration.ofSeconds(30), properties.forOperation(AiOperation.CLASSIFICATION));
        assertEquals(Duration.ofSeconds(30), properties.forOperation(AiOperation.PREFLIGHT));
        assertEquals(Duration.ofSeconds(30), properties.maxTimeout());
    }

    @Test
    void perOperationOverridesWinOverTheDefault() {
        AiTimeoutProperties properties = new AiTimeoutProperties(Map.of(
                "default", Duration.ofSeconds(30),
                "selector-news", Duration.ofSeconds(180),
                "classification", Duration.ofSeconds(45)));

        assertEquals(Duration.ofSeconds(180), properties.forOperation(AiOperation.SELECTOR_NEWS));
        assertEquals(Duration.ofSeconds(45), properties.forOperation(AiOperation.CLASSIFICATION));
        assertEquals(Duration.ofSeconds(30), properties.forOperation(AiOperation.FILTER_APPLY));
        // the client backstop must not cut short the longest operation
        assertEquals(Duration.ofSeconds(180), properties.maxTimeout());
    }

    @Test
    void unknownKeysFailTheConstruction() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new AiTimeoutProperties(Map.of(
                        "classification", Duration.ofSeconds(60),
                        "clasification", Duration.ofSeconds(60))));
        assertEquals(true, e.getMessage().contains("Unknown AI timeout key 'clasification'"));
    }

    @Test
    void nonPositiveValuesFailTheConstruction() {
        assertThrows(IllegalArgumentException.class,
                () -> new AiTimeoutProperties(Map.of("default", Duration.ZERO)));
        assertThrows(IllegalArgumentException.class,
                () -> new AiTimeoutProperties(Map.of(
                        "classification", Duration.ofSeconds(-5))));
    }
}
