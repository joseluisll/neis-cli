package es.jllopez.neis.application;

import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Registers the US-13.8 per-operation AI timeout configuration
 * ({@link AiTimeoutProperties}, {@code neis.ai.timeouts.*}) — same
 * registration pattern as {@link SchedulerConfig} and
 * {@link FilterServiceConfig} — plus the fail-closed key gate.
 */
@Configuration
@EnableConfigurationProperties(AiTimeoutProperties.class)
public class AiTimeoutConfig {

    /**
     * Fail-closed key gate (US-13.8, US-08 review precedent): EVERY
     * key under {@code neis.ai.timeouts} must name the default or a
     * known operation — a typo'd override such as
     * {@code neis.ai.timeouts.selector-new} must FAIL THE BOOT, never
     * silently fall back to the default (the caller would believe it
     * raised the timeout while the default still governs). The logic
     * lives in {@link AiTimeoutProperties#rejectUnknownKeys}; it scans
     * both the yml form and the environment-variable form (the
     * installed service's neis.env mechanism) BEFORE any bean is
     * bound. Static on purpose: BeanFactoryPostProcessor beans
     * register before regular beans exist.
     */
    @Bean
    static BeanFactoryPostProcessor aiTimeoutKeyGate(Environment environment) {
        return factory -> AiTimeoutProperties.rejectUnknownKeys(environment);
    }
}
