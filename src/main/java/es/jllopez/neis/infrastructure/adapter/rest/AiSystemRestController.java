package es.jllopez.neis.infrastructure.adapter.rest;

import es.jllopez.neis.application.AiAssistantService;
import es.jllopez.neis.application.ManagementInfoService;
import es.jllopez.neis.application.SystemInfoService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/**
 * AI + system/dashboards over REST (US-18.1, issue #116). AI calls
 * spend provider budget: the answer is the AI integration point's own
 * refusal when disabled (the service's availability check decides),
 * surfaced as 503.
 */
@RestController
@ConditionalOnServerEnabled
public class AiSystemRestController {

    private final AiAssistantService ai;
    private final SystemInfoService system;
    private final ManagementInfoService management;

    public AiSystemRestController(AiAssistantService ai,
                                  SystemInfoService system,
                                  ManagementInfoService management) {
        this.ai = ai;
        this.system = system;
        this.management = management;
    }

    @GetMapping("/api/ai/status")
    public Map<String, Object> aiStatus() {
        return Map.of("available", ai.isAvailable());
    }

    public record AskRequest(String prompt) {
    }

    @PostMapping("/api/ai/ask")
    public Map<String, String> ask(@RequestBody AskRequest request) {
        if (request.prompt() == null || request.prompt().isBlank()) {
            throw new IllegalArgumentException("prompt is required");
        }
        if (!ai.isAvailable()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "The AI integration point is not available (provider "
                            + "not configured or disabled)");
        }
        return Map.of("answer", ai.complete(request.prompt()));
    }

    @GetMapping("/api/system")
    public Object system() {
        return system.getSystemInfo();
    }

    @GetMapping("/api/dashboard")
    public ManagementInfoService.Dashboard dashboard() {
        return management.dashboard();
    }
}
