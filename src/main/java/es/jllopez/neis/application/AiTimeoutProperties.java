package es.jllopez.neis.application;

import es.jllopez.neis.domain.model.AiOperation;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.Map;

/**
 * Per-operation timeouts for AI service calls (US-13.8, issue #132):
 * {@code neis.ai.timeouts.default} applies to EVERY operation without
 * an explicit value (its configured value is 240 seconds; the same
 * value is the code default when the key is absent), and
 * {@code neis.ai.timeouts.<operation>} overrides one operation. The
 * valid per-operation keys are the {@link AiOperation#configKeys()}
 * — an unknown key is a typo and fails the boot (configuration-time
 * validation rule: syntax at bind time, no database involved).
 *
 * <pre>
 * neis:
 *   ai:
 *     timeouts:
 *       default: 240s          # every operation without an override
 *       selector-news: 180s    # one operation, longer headroom
 * </pre>
 */
@ConfigurationProperties(prefix = "neis.ai.timeouts")
public record AiTimeoutProperties(Map<String, Duration> timeouts) {

    /** The requirement's default: 240 seconds for every operation. */
    public static final Duration DEFAULT_AI_TIMEOUT = Duration.ofSeconds(240);

    /** The key carrying the default for all operations. */
    public static final String DEFAULT_KEY = "default";

    public AiTimeoutProperties {
        timeouts = timeouts == null ? Map.of() : Map.copyOf(timeouts);
        for (Map.Entry<String, Duration> entry : timeouts.entrySet()) {
            String key = entry.getKey();
            Duration value = entry.getValue();
            if (value == null || value.isZero() || value.isNegative()) {
                throw new IllegalArgumentException(
                        "AI timeout '" + key + "' must be positive, got "
                                + value + " (US-13.8)");
            }
            if (!DEFAULT_KEY.equals(key) && !AiOperation.isConfigKey(key)) {
                throw new IllegalArgumentException(
                        "Unknown AI timeout key '" + key
                                + "' — valid per-operation keys: "
                                + AiOperation.configKeys() + " (plus '"
                                + DEFAULT_KEY + "') (US-13.8)");
            }
        }
    }

    /**
     * The effective timeout of one operation: its explicit override,
     * else the configured default, else the 240-second code default.
     */
    public Duration forOperation(AiOperation operation) {
        Duration specific = timeouts.get(operation.configKey());
        if (specific != null) {
            return specific;
        }
        Duration configuredDefault = timeouts.get(DEFAULT_KEY);
        return configuredDefault != null ? configuredDefault
                : DEFAULT_AI_TIMEOUT;
    }

    /**
     * The LONGEST effective timeout — the connector's HTTP client
     * call-timeout backstop (it must never cut short an operation
     * that configured more headroom than the default).
     */
    public Duration maxTimeout() {
        Duration max = timeouts.getOrDefault(DEFAULT_KEY, DEFAULT_AI_TIMEOUT);
        for (AiOperation operation : AiOperation.values()) {
            Duration effective = forOperation(operation);
            if (effective.compareTo(max) > 0) {
                max = effective;
            }
        }
        return max;
    }
}
