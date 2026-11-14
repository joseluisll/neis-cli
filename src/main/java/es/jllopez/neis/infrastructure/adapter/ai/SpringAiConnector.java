package es.jllopez.neis.infrastructure.adapter.ai;

import es.jllopez.neis.domain.port.AiAssistantPort;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Connector (driven adapter) implementing the AI port with Spring AI's
 * OpenAI-compatible chat model.
 *
 * <p>Active only when {@code neis.ai.enabled=true} and an API key is
 * configured, so the application boots (and tests run) with no
 * provider. Provider is MeliousAI (https://api.melious.ai/v1,
 * OpenAI-compatible — the same endpoint the Hermes agent uses) and the
 * key is read from the MELIOUS_API_KEY environment variable; the key
 * never lives in this repository (owner decision 2026-11-11; provider
 * management is US-15.3).</p>
 *
 * <p>Spring AI 2.x wiring note: the model builds its HTTP clients
 * internally from the {@link OpenAiChatOptions} (baseUrl + apiKey +
 * model); no manual OpenAIClient/OkHttp client is needed.</p>
 */
@Component
@ConditionalOnProperty(name = "neis.ai.enabled", havingValue = "true")
public class SpringAiConnector implements AiAssistantPort {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(SpringAiConnector.class);

    private final ChatModel chatModel;
    private final String model;

    public SpringAiConnector(
            @Value("${neis.ai.provider:melious}") String provider,
            @Value("${neis.ai.base-url}") String baseUrl,
            @Value("${neis.ai.model:}") String model,
            @Value("${neis.ai.api-key-ref:MELIOUS_API_KEY}") String apiKeyRef,
            @Value("${neis.ai.api-key:}") String legacyApiKey) {
        // US-15.3 (issue #66): exactly one provider is accepted in this
        // iteration — MeliousAI; configuring any other name is refused
        // until a new owner decision adds it.
        if (!"melious".equalsIgnoreCase(provider == null ? "" : provider.trim())) {
            throw new IllegalArgumentException(
                    "AI provider '" + provider + "' is not accepted (US-15.3): "
                            + "the only configured provider in this iteration is 'melious'");
        }
        // The key comes from the environment variable named by
        // api-key-ref — never from configuration. A neis.ai.api-key
        // value that does not match the environment key is a plain-text
        // key in configuration: rejected (US-15.3).
        String envKey = System.getenv(apiKeyRef == null || apiKeyRef.isBlank()
                ? "MELIOUS_API_KEY" : apiKeyRef.trim());
        if (legacyApiKey != null && !legacyApiKey.isBlank()
                && !legacyApiKey.equals(envKey)) {
            throw new IllegalArgumentException(
                    "neis.ai.api-key holds a plain-text key (US-15.3): keys are "
                            + "resolved from the environment variable named by "
                            + "neis.ai.api-key-ref (" + apiKeyRef + ") — remove the "
                            + "literal value from configuration");
        }
        if (envKey == null || envKey.isBlank()) {
            throw new IllegalStateException(
                    "AI enabled but the API key is missing: set the environment "
                            + "variable " + apiKeyRef + " (US-15.3)");
        }
        this.model = model == null || model.isBlank() ? "(provider default)" : model;
        log.info("AI provider: {} ({}) model {} — key from environment variable {}",
                provider, baseUrl, this.model, apiKeyRef);
        OpenAiChatOptions.Builder options = OpenAiChatOptions.builder()
                .baseUrl(baseUrl)
                .apiKey(envKey)
                // Deterministic analysis answers: the chain's decisions
                // (selectors, capability flags, filter choice) must be
                // reproducible across runs of the same page.
                .temperature(0.0);
        if (model != null && !model.isBlank()) {
            options.model(model);
        }
        this.chatModel = OpenAiChatModel.builder()
                .options(options.build())
                .build();
    }

    /**
     * One explicit trace per AI call: model, prompt/answer sizes and
     * elapsed time — the operational shape of the call, never its
     * protocol (no prompt or answer bodies).
     */
    @Override
    public String complete(String prompt) {
        long start = System.nanoTime();
        try {
            String answer = chatModel.call(prompt);
            log.info("[AI] call: model={}, prompt {} chars -> answer {} chars in {} ms",
                    model, prompt.length(), answer == null ? 0 : answer.length(),
                    (System.nanoTime() - start) / 1_000_000);
            return answer;
        } catch (RuntimeException e) {
            log.warn("[AI] call failed after {} ms: {}: {}",
                    (System.nanoTime() - start) / 1_000_000,
                    e.getClass().getSimpleName(), e.getMessage());
            throw e;
        }
    }
}
