package es.jllopez.neis.application;

import es.jllopez.neis.domain.port.AiAssistantPort;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * Use case: AI-assisted operations through the domain AI port.
 *
 * <p>The connector behind the port is optional at runtime: the AI
 * integration point (US-01.3) is active only when infrastructure
 * supplies an implementation (provider configured). Depends only on
 * the domain port, never on infrastructure.</p>
 */
@Service
public class AiAssistantService {

    private final ObjectProvider<AiAssistantPort> aiAssistantPort;

    public AiAssistantService(ObjectProvider<AiAssistantPort> aiAssistantPort) {
        this.aiAssistantPort = aiAssistantPort;
    }

    /** True when infrastructure supplies an AI connector. */
    public boolean isAvailable() {
        return aiAssistantPort.getIfAvailable() != null;
    }

    public String complete(String prompt) {
        AiAssistantPort port = aiAssistantPort.getIfAvailable();
        if (port == null) {
            throw new IllegalStateException(
                    "AI integration point is not configured"
                    + " (enable neis.ai.enabled and provide neis.ai.api-key)");
        }
        return port.complete(prompt);
    }
}
