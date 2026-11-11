package es.jllopez.neis.domain.port;

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

    String complete(String prompt);
}
