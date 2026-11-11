package es.jllopez.neis.infrastructure.adapter.ai;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
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
 * provider. Default provider is MeliousAI (https://api.melious.ai,
 * OpenAI-compatible) per the owner's stack decision (issue #63;
 * provider management is US-15.3).</p>
 */
@Component
@ConditionalOnProperty(name = "neis.ai.enabled", havingValue = "true")
public class SpringAiConnector implements AiAssistantPort {

    private final ChatModel chatModel;

    public SpringAiConnector(
            @Value("${neis.ai.api-key}") String apiKey,
            @Value("${neis.ai.base-url}") String baseUrl,
            @Value("${neis.ai.model:}") String model) {
        OpenAIClient client = OpenAIOkHttpClient.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .build();
        OpenAiChatModel.Builder builder = OpenAiChatModel.builder()
                .openAiClient(client);
        if (model != null && !model.isBlank()) {
            builder.options(OpenAiChatOptions.builder().model(model).build());
        }
        this.chatModel = builder.build();
    }

    @Override
    public String complete(String prompt) {
        return chatModel.call(prompt);
    }
}
