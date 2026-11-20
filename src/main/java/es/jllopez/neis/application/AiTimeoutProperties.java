package es.jllopez.neis.application;

import es.jllopez.neis.domain.model.AiOperation;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.Name;

import java.time.Duration;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Per-operation timeouts for AI service calls (US-13.8, issue #132):
 * {@code neis.ai.timeouts.default} applies to EVERY operation without
 * an explicit value (its configured value is 240 seconds; the same
 * value is the code default when the key is absent), and
 * {@code neis.ai.timeouts.<operation>} overrides one operation.
 *
 * <p>FLAT fields, not a map — on purpose (live finding): Map binding
 * from ENVIRONMENT variables (the installed service's neis.env) does
 * not reproduce map keys reliably (NEIS_AI_TIMEOUTS_DEFAULT never
 * reached the map), while flat record components bind through relaxed
 * binding exactly like every other key (NEIS_AI_MODEL precedent).
 * Each operation has one field; the property names are the
 * requirement's keys: {@code neis.ai.timeouts.classification},
 * {@code ...selector-news}, {@code ...selector-events},
 * {@code ...capability-detection}, {@code ...capability-repair},
 * {@code ...filter-zone-selector}, {@code ...filter-criterion},
 * {@code ...filter-apply}, {@code ...registration},
 * {@code ...preflight}, {@code ...generic}. A new AI operation adds
 * its enum constant + its field — it can never bypass the mechanism.
 * Validation at bind time: every present value must be positive.</p>
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
public record AiTimeoutProperties(
        @Name("default") Duration defaultTimeout,
        Duration generic,
        Duration classification,
        Duration selectorNews,
        Duration selectorEvents,
        Duration capabilityDetection,
        Duration capabilityRepair,
        Duration filterZoneSelector,
        Duration filterCriterion,
        Duration filterApply,
        Duration registration,
        Duration preflight) {

    /** The requirement's default: 240 seconds for every operation. */
    public static final Duration DEFAULT_AI_TIMEOUT = Duration.ofSeconds(240);

    /** The property prefix (also used by the key gate). */
    public static final String PREFIX = "neis.ai.timeouts";

    /** The environment-variable form of the prefix (key gate). */
    public static final String ENV_PREFIX = "NEIS_AI_TIMEOUTS_";

    public AiTimeoutProperties {
        defaultTimeout = positiveOrNull(defaultTimeout, "default");
        generic = positiveOrNull(generic, "generic");
        classification = positiveOrNull(classification, "classification");
        selectorNews = positiveOrNull(selectorNews, "selector-news");
        selectorEvents = positiveOrNull(selectorEvents, "selector-events");
        capabilityDetection = positiveOrNull(capabilityDetection,
                "capability-detection");
        capabilityRepair = positiveOrNull(capabilityRepair,
                "capability-repair");
        filterZoneSelector = positiveOrNull(filterZoneSelector,
                "filter-zone-selector");
        filterCriterion = positiveOrNull(filterCriterion, "filter-criterion");
        filterApply = positiveOrNull(filterApply, "filter-apply");
        registration = positiveOrNull(registration, "registration");
        preflight = positiveOrNull(preflight, "preflight");
    }

    private static Duration positiveOrNull(Duration value, String key) {
        if (value == null) {
            return null;
        }
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(
                    "AI timeout 'neis.ai.timeouts." + key
                            + "' must be positive, got " + value
                            + " (US-13.8)");
        }
        return value;
    }

    /**
     * The effective timeout of one operation: its explicit override,
     * else the configured default, else the 240-second code default.
     */
    public Duration forOperation(AiOperation operation) {
        Duration specific = switch (operation) {
            case GENERIC -> generic;
            case CLASSIFICATION -> classification;
            case SELECTOR_NEWS -> selectorNews;
            case SELECTOR_EVENTS -> selectorEvents;
            case CAPABILITY_DETECTION -> capabilityDetection;
            case CAPABILITY_REPAIR -> capabilityRepair;
            case FILTER_ZONE_SELECTOR -> filterZoneSelector;
            case FILTER_CRITERION -> filterCriterion;
            case FILTER_APPLY -> filterApply;
            case REGISTRATION -> registration;
            case PREFLIGHT -> preflight;
        };
        if (specific != null) {
            return specific;
        }
        return defaultTimeout != null ? defaultTimeout : DEFAULT_AI_TIMEOUT;
    }

    /**
     * The LONGEST effective timeout — the connector's HTTP client
     * call-timeout backstop (it must never cut short an operation
     * that configured more headroom than the default).
     */
    public Duration maxTimeout() {
        Duration max = defaultTimeout != null ? defaultTimeout
                : DEFAULT_AI_TIMEOUT;
        for (AiOperation operation : AiOperation.values()) {
            Duration effective = forOperation(operation);
            if (effective.compareTo(max) > 0) {
                max = effective;
            }
        }
        return max;
    }

    /**
     * The gate's logic (invoked by {@link AiTimeoutConfig} as a
     * BeanFactoryPostProcessor): throws naming the first unknown key
     * found under {@link #PREFIX} — Spring constructor binding cannot
     * honor {@code ignoreUnknownFields=false} (verified live: unknown
     * keys are simply not bound), so the gate scans the environment
     * itself, yml-style AND environment-variable style, BEFORE any
     * bean is bound.
     */
    public static void rejectUnknownKeys(
            org.springframework.core.env.Environment environment) {
        Set<String> known = new HashSet<>(Arrays.asList("default"));
        for (AiOperation operation : AiOperation.values()) {
            known.add(operation.configKey());
        }
        for (var source : ((org.springframework.core.env.ConfigurableEnvironment)
                environment).getPropertySources()) {
            if (!(source instanceof org.springframework.core.env.
                    EnumerablePropertySource<?> enumerable)) {
                continue;
            }
            for (String name : enumerable.getPropertyNames()) {
                String key = timeoutKeyOf(name);
                if (key != null && !known.contains(key)) {
                    throw new IllegalStateException(
                            "Unknown AI timeout key 'neis.ai.timeouts."
                                    + key + "' — known keys: default, "
                                    + "generic, classification, "
                                    + "selector-news, selector-events, "
                                    + "capability-detection, "
                                    + "capability-repair, "
                                    + "filter-zone-selector, "
                                    + "filter-criterion, filter-apply, "
                                    + "registration, preflight (US-13.8: "
                                    + "a typo'd override would be "
                                    + "silently ignored, so the boot "
                                    + "fails instead)");
                }
            }
        }
    }

    /**
     * Extracts the timeout key from a property name, accepting both
     * the yml form ({@code neis.ai.timeouts.selector-news}) and the
     * environment-variable form ({@code NEIS_AI_TIMEOUTS_SELECTOR_NEWS}
     * — relaxed to {@code selector-news}). Returns null for names
     * outside the prefix.
     */
    private static String timeoutKeyOf(String name) {
        if (name.startsWith(PREFIX + ".")) {
            return name.substring(PREFIX.length() + 1);
        }
        if (name.startsWith(ENV_PREFIX)) {
            return name.substring(ENV_PREFIX.length())
                    .toLowerCase(java.util.Locale.ROOT).replace('_', '-');
        }
        return null;
    }
}
