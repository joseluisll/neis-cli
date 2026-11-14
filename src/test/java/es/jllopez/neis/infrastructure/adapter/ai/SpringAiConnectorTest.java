package es.jllopez.neis.infrastructure.adapter.ai;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The AI provider management boundary (US-15.3, issue #66): only
 * MeliousAI is accepted; the key comes from the environment variable
 * named by neis.ai.api-key-ref — a plain-text key in configuration is
 * rejected, and a missing environment key fails the connector
 * construction (the bean is only created when neis.ai.enabled=true).
 * The happy path needs a real environment key and is verified live.
 */
class SpringAiConnectorTest {

    @Test
    void otherProvidersAreRefused() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new SpringAiConnector("openai", "https://api.openai.com/v1",
                        "gpt-4o", "NO_SUCH_VAR_XYZ", ""));
        assertTrue(e.getMessage().contains("not accepted"), e.getMessage());
        assertTrue(e.getMessage().contains("melious"), e.getMessage());
    }

    @Test
    void plainTextKeysInConfigurationAreRejected() {
        // A literal neis.ai.api-key that does not match the environment
        // key is a plain-text key in configuration: rejected.
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new SpringAiConnector("melious", "https://api.melious.ai/v1",
                        "glm-5.3-flash", "NO_SUCH_VAR_XYZ", "sk-hardcoded-key"));
        assertTrue(e.getMessage().contains("plain-text"), e.getMessage());
    }

    @Test
    void missingEnvironmentKeyFailsTheConstruction() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new SpringAiConnector("melious", "https://api.melious.ai/v1",
                        "glm-5.3-flash", "NO_SUCH_VAR_XYZ", ""));
        assertTrue(e.getMessage().contains("API key is missing"), e.getMessage());
        assertTrue(e.getMessage().contains("NO_SUCH_VAR_XYZ"), e.getMessage());
    }
}
