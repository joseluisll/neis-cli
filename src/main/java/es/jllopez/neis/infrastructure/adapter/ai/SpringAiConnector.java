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
public class SpringAiConnector implements AiAssistantPort,
        org.springframework.beans.factory.DisposableBean {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(SpringAiConnector.class);

    /** The default environment variable holding the API key (shared by
     * the @Value default and the effective-ref fallback so they cannot
     * drift — US-15.3 review note, 2026-11-14). */
    static final String DEFAULT_API_KEY_REF = "MELIOUS_API_KEY";

    private final ChatModel chatModel;
    private final String model;
    /**
     * US-13.8 (issue #132): per-operation timeouts. Calls run on a
     * dedicated daemon pool and are bounded by the operation's
     * configured timeout; the HTTP client itself carries a hard
     * call-timeout at the LONGEST configured timeout, so even a
     * timed-out-and-cancelled call cannot pin its worker thread
     * forever (the flash-stall incident: a selector call blocked on
     * response headers for 4+ minutes with no timeout anywhere).
     */
    private final es.jllopez.neis.application.AiTimeoutProperties aiTimeouts;
    private final java.util.concurrent.ExecutorService aiCallExecutor;

    // @Autowired: two constructors exist (the test-visible model
    // injection is package-private), so the autowire candidate must be
    // explicit
    @org.springframework.beans.factory.annotation.Autowired
    public SpringAiConnector(
            @Value("${neis.ai.provider:melious}") String provider,
            @Value("${neis.ai.base-url}") String baseUrl,
            @Value("${neis.ai.model:}") String model,
            @Value("${neis.ai.api-key-ref:" + DEFAULT_API_KEY_REF + "}") String apiKeyRef,
            org.springframework.core.env.Environment environment,
            es.jllopez.neis.application.AiTimeoutProperties aiTimeouts) {
        this(resolveChatModel(provider, baseUrl, model, apiKeyRef,
                environment, aiTimeouts), model, aiTimeouts);
    }

    /**
     * Test-visible: injects the chat model directly — no provider
     * credentials involved. The timeout machinery is what the tests
     * pin; the provider boundary tests live in
     * {@code SpringAiConnectorTest}.
     */
    SpringAiConnector(ChatModel chatModel, String model,
                      es.jllopez.neis.application.AiTimeoutProperties aiTimeouts) {
        this.chatModel = chatModel;
        this.model = model == null || model.isBlank() ? "(provider default)" : model;
        this.aiTimeouts = aiTimeouts;
        this.aiCallExecutor = java.util.concurrent.Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "ai-calls");
            t.setDaemon(true);
            return t;
        });
    }

    private static ChatModel resolveChatModel(
            String provider, String baseUrl, String model, String apiKeyRef,
            org.springframework.core.env.Environment environment,
            es.jllopez.neis.application.AiTimeoutProperties aiTimeouts) {
        // US-15.3 (issue #66): exactly one provider is accepted in this
        // iteration — MeliousAI; configuring any other name is refused
        // until a new owner decision adds it.
        if (!"melious".equalsIgnoreCase(provider == null ? "" : provider.trim())) {
            throw new IllegalArgumentException(
                    "AI provider '" + provider + "' is not accepted (US-15.3): "
                            + "the only configured provider in this iteration is 'melious'");
        }
        String effectiveRef = apiKeyRef == null || apiKeyRef.isBlank()
                ? DEFAULT_API_KEY_REF : apiKeyRef.trim();
        // The key comes from the environment variable named by
        // api-key-ref — never from configuration. The legacy
        // neis.ai.api-key property is read through the Environment (NOT
        // via a @Value placeholder: a credential-sanitizer mangled the
        // "${...:}" literal into an unbootable default — blocker of the
        // US-15.3 review, 2026-11-14). A legacy value that does not
        // match the environment key is a plain-text key in
        // configuration: rejected; the old masked '***' placeholder
        // gets its own migration message.
        String legacyApiKey = environment.getProperty("neis.ai.api-key", "");
        String envKey = System.getenv(effectiveRef);
        if (legacyApiKey != null && !legacyApiKey.isBlank()
                && !legacyApiKey.equals(envKey)) {
            if ("***".equals(legacyApiKey.trim())) {
                throw new IllegalArgumentException(
                        "neis.ai.api-key holds the old masked placeholder '***' "
                                + "(US-15.3): remove it from configuration — the key is "
                                + "resolved from the environment variable named by "
                                + "neis.ai.api-key-ref (" + effectiveRef + ")");
            }
            throw new IllegalArgumentException(
                    "neis.ai.api-key holds a plain-text key (US-15.3): keys are "
                            + "resolved from the environment variable named by "
                            + "neis.ai.api-key-ref (" + effectiveRef + ") — remove the "
                            + "literal value from configuration");
        }
        if (envKey == null || envKey.isBlank()) {
            throw new IllegalStateException(
                    "AI enabled but the API key is missing: set the environment "
                            + "variable " + effectiveRef + " (US-15.3)");
        }
        String effectiveModel = model == null || model.isBlank()
                ? "(provider default)" : model;
        log.info("AI provider: {} ({}) model {} — key from environment variable {}",
                provider, baseUrl, effectiveModel, effectiveRef);
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
        return OpenAiChatModel.builder()
                .options(options.build())
                // US-13.8: the client-side backstop at the LONGEST
                // configured timeout — the per-operation timeouts bound
                // the CALLER, this bounds the HTTP exchange itself (a
                // stalled response can no longer pin a thread forever).
                .httpClientBuilderCustomizer(builder ->
                        builder.timeout(aiTimeouts.maxTimeout()))
                .build();
    }

    /**
     * The generic AI ask (AI task type, CLI/REST ask) — runs under
     * the configured default timeout (US-13.8).
     */
    @Override
    public String complete(String prompt) {
        return doTimed(es.jllopez.neis.domain.model.AiOperation.GENERIC,
                prompt);
    }

    /**
     * US-13.8: the per-operation entry — the operation's configured
     * timeout bounds the call; a timeout surfaces as
     * {@link es.jllopez.neis.domain.port.AiCallTimedOutException} so
     * the caller's existing AI-unavailable fallback path runs.
     */
    @Override
    public String complete(es.jllopez.neis.domain.model.AiOperation operation,
                           String prompt) {
        return doTimed(operation, prompt);
    }

    private String doTimed(es.jllopez.neis.domain.model.AiOperation operation,
                           String prompt) {
        java.time.Duration timeout = aiTimeouts.forOperation(operation);
        long start = System.nanoTime();
        java.util.concurrent.Future<String> future =
                aiCallExecutor.submit(() -> chatModel.call(prompt));
        try {
            String answer = future.get(timeout.toMillis(),
                    java.util.concurrent.TimeUnit.MILLISECONDS);
            log.info("[AI] call: operation={}, model={}, prompt {} chars -> "
                            + "answer {} chars in {} ms (timeout {})",
                    operation.configKey(), model, prompt.length(),
                    answer == null ? 0 : answer.length(),
                    (System.nanoTime() - start) / 1_000_000, timeout);
            return answer;
        } catch (java.util.concurrent.TimeoutException e) {
            future.cancel(true);
            log.warn("[AI] operation '{}' TIMED OUT after {} (effective "
                            + "timeout {} ms elapsed) — AI unavailable, the "
                            + "caller falls back", operation.configKey(),
                    timeout, (System.nanoTime() - start) / 1_000_000);
            throw new es.jllopez.neis.domain.port.AiCallTimedOutException(
                    operation, timeout);
        } catch (java.util.concurrent.ExecutionException e) {
            long elapsed = (System.nanoTime() - start) / 1_000_000;
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            log.warn("[AI] call failed after {} ms: {}: {}",
                    elapsed, cause.getClass().getSimpleName(),
                    cause.getMessage());
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException(
                    "AI call failed: " + cause.getMessage(), cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "Interrupted while waiting for the AI call ("
                            + operation.configKey() + ")", e);
        }
    }

    /** Shuts the bounded AI-call pool down (its threads are daemons). */
    @Override
    public void destroy() {
        aiCallExecutor.shutdownNow();
    }
}
