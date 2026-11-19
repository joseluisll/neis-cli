package es.jllopez.neis.application;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the US-13.8 per-operation AI timeout configuration
 * ({@link AiTimeoutProperties}, {@code neis.ai.timeouts.*}) — same
 * registration pattern as {@link SchedulerConfig} and
 * {@link FilterServiceConfig}.
 */
@Configuration
@EnableConfigurationProperties(AiTimeoutProperties.class)
public class AiTimeoutConfig {
}
