package es.jllopez.neis.domain.model;

import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The AI operations of the SSAFP pipeline and the registration flow
 * (US-13.8, issue #132): every AI call belongs to ONE operation, and
 * each operation carries its own configurable timeout key
 * ({@code neis.ai.timeouts.<configKey>}). An operation added later
 * MUST register here — the requirement's "any further AI operation
 * registers its timeout key with the default, never bypasses the
 * mechanism" rule.
 *
 * <p>Operations without a call site today (PREFLIGHT — US-13.6 wires
 * its capture without AI for now) stay in the vocabulary so their
 * timeout key is already configurable when the call arrives.</p>
 */
public enum AiOperation {

    /** The generic AI ask (AI task type, CLI/REST ask, MCP-free). */
    GENERIC("generic"),
    /** ANALYZE: news / event / other classification. */
    CLASSIFICATION("classification"),
    /** ANALYZE: the news-list selector identification (US-13.3). */
    SELECTOR_NEWS("selector-news"),
    /** ANALYZE: the events-list selector identification (US-13.4). */
    SELECTOR_EVENTS("selector-events"),
    /** ANALYZE: pagination/filtering capability detection (US-17.2). */
    CAPABILITY_DETECTION("capability-detection"),
    /** ANALYZE: the one AI repair round of a detected page-URL pattern. */
    CAPABILITY_REPAIR("capability-repair"),
    /** FILTER: the filter-zone selector identification. */
    FILTER_ZONE_SELECTOR("filter-zone-selector"),
    /** FILTER: the filter fields/values extraction of a listing. */
    FILTER_CRITERION("filter-criterion"),
    /** FILTER: choosing/applying a criterion to an outcome. */
    FILTER_APPLY("filter-apply"),
    /** Registration assistance: auto-populated fields (US-13.5). */
    REGISTRATION("registration"),
    /** Address preflight capture, when AI-assisted (US-13.6). */
    PREFLIGHT("preflight");

    private final String configKey;

    AiOperation(String configKey) {
        this.configKey = configKey;
    }

    /** The {@code neis.ai.timeouts.<key>} key of this operation. */
    public String configKey() {
        return configKey;
    }

    /** All valid per-operation timeout keys ("default" excluded). */
    public static Set<String> configKeys() {
        return Stream.of(values()).map(AiOperation::configKey)
                .collect(Collectors.toUnmodifiableSet());
    }

    /** True when {@code key} is a valid per-operation timeout key. */
    public static boolean isConfigKey(String key) {
        return configKeys().contains(key);
    }
}
