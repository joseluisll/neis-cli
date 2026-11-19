package es.jllopez.neis.domain.port;

import es.jllopez.neis.domain.model.AiOperation;

import java.time.Duration;

/**
 * An AI operation exceeded its configured timeout (US-13.8, issue
 * #132). Callers treat it exactly like any other AI-unavailable
 * failure: the existing fallback/heuristic path runs — a timed-out
 * operation is never a hung executor thread or a permanently RUNNING
 * job. The message names the operation, its effective timeout and the
 * configuration key that governs it.
 */
public class AiCallTimedOutException extends RuntimeException {

    public AiCallTimedOutException(AiOperation operation, Duration timeout) {
        super("AI operation '" + operation.configKey() + "' timed out after "
                + timeout + " — raise neis.ai.timeouts."
                + operation.configKey() + " (or neis.ai.timeouts.default) "
                + "if the model needs more headroom; the caller falls "
                + "back to the non-AI path");
    }
}
