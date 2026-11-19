package es.jllopez.neis.application;

import es.jllopez.neis.domain.model.AddressType;
import es.jllopez.neis.domain.model.AiOperation;
import es.jllopez.neis.domain.port.AiAssistantPort;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Use case: AI-assisted address registration (US-13.5, issue #64).
 * Given only a URL, the AI proposes the address fields — suggested
 * name, expected type (news, event or other), description and any
 * metadata the source exposes — by reading the simplified entry page.
 *
 * <p>The proposal is a SUGGESTION: the user confirms, edits or rejects
 * it before anything is saved (US-03). This service never writes the
 * catalog — it returns the proposal; the shell command applies it
 * through {@link AddressCatalogService#create} only after the user's
 * explicit confirmation. It works through the provider-agnostic AI
 * integration point (US-13.1); with no AI configured the use case
 * fails loud (an un-AI'd suggestion engine would be a different
 * feature).</p>
 */
@Service
public class AddressSuggestionService {

    private final ObjectProvider<AiAssistantPort> aiPort;
    private final PipelineService pipeline;

    public AddressSuggestionService(ObjectProvider<AiAssistantPort> aiPort,
                                    PipelineService pipeline) {
        this.aiPort = aiPort;
        this.pipeline = pipeline;
    }

    /** One AI-proposed address registration. */
    public record Suggestion(String url, String name, AddressType type,
                             String description, Map<String, String> metadata) {
    }

    /**
     * Proposes the address fields for the URL: fetches and simplifies
     * the entry page, asks the AI through the prompt catalog, and
     * parses the lenient JSON contract. Unknown/unconfigured AI fails
     * loud; a malformed answer yields a null type (the caller asks the
     * user for the missing pieces rather than guessing).
     */
    public Suggestion suggest(String url) {
        AiAssistantPort ai = aiPort.getIfAvailable();
        if (ai == null) {
            throw new IllegalStateException(
                    "The address suggestion needs the AI integration point"
                    + " (US-13.5) — none is configured (set neis.ai.enabled=true"
                    + " and provide the API key named by neis.ai.api-key-ref,"
                    + " default MELIOUS_API_KEY)");
        }
        // Fetch + simplify the entry page (the same path the AI task
        // uses): the AI reads the cleansed HTML5, not the raw page.
        var scrape = pipeline.scrapePort.scrape(
                new es.jllopez.neis.domain.model.Address(
                        "SUGGEST", null, null, url, AddressType.URL_NEWS));
        var cleared = pipeline.clearObstructions(
                new es.jllopez.neis.domain.model.Address(
                        "SUGGEST", null, null, url, AddressType.URL_NEWS),
                scrape.html());
        var simplified = pipeline.simplifyPort.simplify(cleared.html());

        String answer = ai.complete(AiOperation.REGISTRATION,
                AiPromptCatalog.addressSuggestionPrompt(url, simplified.html()));
        return parseSuggestion(url, answer);
    }

    /** Lenient parse of the suggestion JSON contract. Package-private for tests. */
    static Suggestion parseSuggestion(String url, String answer) {
        String name = field(answer, "name");
        String typeWord = field(answer, "type");
        String description = field(answer, "description");
        AddressType type = switch (typeWord == null ? "" : typeWord.trim().toLowerCase()) {
            case "news" -> AddressType.URL_NEWS;
            case "event", "events" -> AddressType.URL_EVENTS;
            default -> null; // 'other' or unrecognized: the user decides
        };
        Map<String, String> metadata = new LinkedHashMap<>();
        Matcher m = Pattern.compile(
                        "\"metadata\"\\s*:\\s*\\{(.*)}", Pattern.DOTALL)
                .matcher(answer);
        if (m.find()) {
            Matcher kv = Pattern.compile("\"([^\"]+)\"\\s*:\\s*\"([^\"]*)\"")
                    .matcher(m.group(1));
            while (kv.find()) {
                metadata.put(kv.group(1), kv.group(2));
            }
        }
        return new Suggestion(url, name, type, description,
                Map.copyOf(metadata));
    }

    /** The string value of one JSON field; null when absent/null. */
    private static String field(String json, String name) {
        if (json == null) {
            return null;
        }
        Matcher m = Pattern.compile(
                        "\"" + name + "\"\\s*:\\s*(null|\"([^\"]*)\")")
                .matcher(json);
        if (!m.find() || m.group(1).equals("null")) {
            return null;
        }
        return m.group(2);
    }
}
