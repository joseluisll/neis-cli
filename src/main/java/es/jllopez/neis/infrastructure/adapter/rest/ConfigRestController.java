package es.jllopez.neis.infrastructure.adapter.rest;

import es.jllopez.neis.application.SettingsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Configuration over REST (US-18.1, issue #116): the runtime-editable
 * settings behind {@link SettingsService}'s allowlist. SECRETS ARE
 * NEVER RETURNED: only the allowlisted keys are exposed, and any key
 * whose name smells like a credential is redacted even if a future
 * allowlist entry matched (defense in depth — the redaction must not
 * depend on today's key list).
 */
@RestController
@RequestMapping("/api/config")
@ConditionalOnServerEnabled
public class ConfigRestController {

    private final SettingsService settings;

    public ConfigRestController(SettingsService settings) {
        this.settings = settings;
    }

    @GetMapping
    public Map<String, Object> config() {
        Map<String, Object> entries = new LinkedHashMap<>();
        for (String key : SettingsService.EDITABLE_KEYS) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("value", redact(key, settings.effective(key)));
            // an absent override is a NORMAL state — a nullable map entry,
            // not Map.of (which rejects nulls with NPE — found live)
            entry.put("override", settings.overrideOf(key)
                    .map(v -> redact(key, v)).orElse(null));
            entries.put(key, entry);
        }
        return Map.of("editable", entries);
    }

    @GetMapping("/{key}")
    public Map<String, String> setting(@PathVariable String key) {
        if (!settings.isEditable(key)) {
            throw new IllegalArgumentException("Unknown or non-editable setting '"
                    + key + "' — editable: " + SettingsService.EDITABLE_KEYS);
        }
        return Map.of("key", key,
                "value", redact(key, settings.effective(key)));
    }

    public record SettingValue(String value) {
    }

    @PutMapping("/{key}")
    public Map<String, String> set(@PathVariable String key,
                                   @RequestBody SettingValue body) {
        settings.set(key, body.value());
        return Map.of("key", key,
                "value", redact(key, settings.effective(key)));
    }

    private static final List<String> SECRET_FRAGMENTS =
            List.of("key", "secret", "token", "password", "credential");

    private String redact(String key, String value) {
        String lower = key.toLowerCase();
        boolean looksSecret = SECRET_FRAGMENTS.stream().anyMatch(lower::contains);
        return looksSecret ? "(redacted)" : value;
    }
}
