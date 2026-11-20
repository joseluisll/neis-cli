package es.jllopez.neis.infrastructure.adapter.ai;

import es.jllopez.neis.application.AiTimeoutProperties;
import es.jllopez.neis.domain.model.AiOperation;
import es.jllopez.neis.domain.port.AiCallTimedOutException;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * US-13.8 (issue #132): the connector bounds every AI call by its
 * operation's configured timeout — a slow model answer surfaces as
 * {@link AiCallTimedOutException} (the caller's AI-unavailable
 * fallback path) while an operation with more headroom completes.
 * Constructed through the package-private model-injection constructor
 * so no provider credentials are involved.
 */
class SpringAiConnectorTimeoutTest {

    private static final long SLOW_MILLIS = 500;

    /** A ChatModel that answers after SLOW_MILLIS. */
    private static ChatModel slowModel() {
        return new ChatModel() {
            @Override
            public String call(String prompt) {
                try {
                    Thread.sleep(SLOW_MILLIS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return "answer";
            }

            @Override
            public ChatResponse call(Prompt prompt) {
                throw new UnsupportedOperationException();
            }

            @Override
            public ChatOptions getDefaultOptions() {
                return null;
            }
        };
    }

    /** Flat-field construction helper: default + named overrides. */
    private static AiTimeoutProperties properties(Duration dflt,
            Duration classification, Duration selectorNews) {
        return new AiTimeoutProperties(dflt, null, classification,
                selectorNews, null, null, null, null, null, null, null,
                null);
    }

    private SpringAiConnector connector(AiTimeoutProperties properties) {
        return new SpringAiConnector(slowModel(), "test-model", properties);
    }

    @Test
    void slowOperationTimesOutWithItsConfiguredBound() {
        SpringAiConnector connector = connector(properties(
                null, Duration.ofMillis(80), null));

        long start = System.nanoTime();
        AiCallTimedOutException e = assertThrows(AiCallTimedOutException.class,
                () -> connector.complete(AiOperation.CLASSIFICATION, "prompt"));
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

        assertTrue(e.getMessage().contains("classification"), e.getMessage());
        assertTrue(e.getMessage().contains("PT0.08S"), e.getMessage());
        // the caller is released AT the timeout, not at the model's pace
        assertTrue(elapsedMillis < SLOW_MILLIS,
                "the caller must be released at the configured timeout, "
                        + "took " + elapsedMillis + "ms");
    }

    @Test
    void operationWithMoreHeadroomCompletes() {
        SpringAiConnector connector = connector(properties(
                null, Duration.ofMillis(80), Duration.ofSeconds(10)));

        assertEquals("answer",
                connector.complete(AiOperation.SELECTOR_NEWS, "prompt"));
    }

    @Test
    void genericCallsRunUnderTheDefaultTimeout() {
        SpringAiConnector connector = connector(properties(
                Duration.ofMillis(80), null, null));

        AiCallTimedOutException e = assertThrows(AiCallTimedOutException.class,
                () -> connector.complete("prompt"));
        assertTrue(e.getMessage().contains("generic"), e.getMessage());
    }

    @Test
    void fastModelAnswersUnderAnyTimeout() {
        SpringAiConnector connector = new SpringAiConnector(
                fastModel(), "test-model",
                properties(Duration.ofSeconds(30), null, null));

        assertEquals("quick", connector.complete("prompt"));
        assertEquals("quick", connector.complete(
                AiOperation.CLASSIFICATION, "prompt"));
    }

    private static ChatModel fastModel() {
        return new ChatModel() {
            @Override
            public String call(String prompt) {
                return "quick";
            }

            @Override
            public ChatResponse call(Prompt prompt) {
                throw new UnsupportedOperationException();
            }

            @Override
            public ChatOptions getDefaultOptions() {
                return null;
            }
        };
    }
}
