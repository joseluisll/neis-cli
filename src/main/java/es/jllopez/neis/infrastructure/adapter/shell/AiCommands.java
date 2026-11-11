package es.jllopez.neis.infrastructure.adapter.shell;

import es.jllopez.neis.application.AiAssistantService;
import org.springframework.shell.core.command.annotation.Command;
import org.springframework.shell.core.command.annotation.CommandGroup;
import org.springframework.stereotype.Component;

/**
 * Driving adapter: Spring Shell commands for the AI integration point.
 * Thin — delegates to application use cases.
 */
@Component
@CommandGroup(name = "AI", description = "AI integration commands")
public class AiCommands {

    private final AiAssistantService aiAssistantService;

    public AiCommands(AiAssistantService aiAssistantService) {
        this.aiAssistantService = aiAssistantService;
    }

    @Command(name = "ai-status", description = "Show AI integration point status")
    public String aiStatus() {
        if (aiAssistantService.isAvailable()) {
            return "AI integration point: active (Spring AI connector)";
        }
        return "AI integration point: not configured"
                + " (set neis.ai.enabled=true and neis.ai.api-key to activate)";
    }
}
