package es.jllopez.neis.domain.port;

import es.jllopez.neis.domain.model.AiOperation;

/**
 * Port for AI-assisted capabilities (extraction support,
 * summarization, field prediction, ...).
 *
 * <p>Owned by the domain; infrastructure provides a connector
 * (adapter) implementing it — e.g. Spring AI behind an
 * OpenAI-compatible provider such as MeliousAI. The domain never
 * knows which provider or library sits behind it.</p>
 */
public interface AiAssistantPort {

    /**
     * The GENERIC AI call: the AI task type, the CLI/REST ask, any
     * caller that does not carry an operation identity. Runs under
     * the {@code neis.ai.timeouts} default (US-13.8).
     */
    String complete(String prompt);

    /**
     * The per-operation AI call (US-13.8, issue #132): every SSAFP
     * pipeline and registration call names its operation, so the
     * connector can enforce the operation's configured timeout
     * ({@code neis.ai.timeouts.<operation.configKey()>}, else the
     * default). A timed-out call surfaces as a RuntimeException —
     * the caller's existing AI-unavailable fallback path. The default
     * implementation keeps the generic behavior: test doubles that
     * implement only {@link #complete(String)} need no change, and
     * the real connector overrides this to enforce the timeout.
     */
    default String complete(AiOperation operation, String prompt) {
        return complete(prompt);
    }
}
