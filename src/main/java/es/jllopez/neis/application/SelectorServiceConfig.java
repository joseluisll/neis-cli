package es.jllopez.neis.application;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Assembly configuration for the selector configuration slice
 * (US-12.1, issue #46): enables the {@code neis.selectors} properties
 * and exposes the preconfigured-selector catalog as a bean.
 */
@Configuration
@EnableConfigurationProperties(PreconfiguredSelectorProperties.class)
public class SelectorServiceConfig {

    @Bean
    public PreconfiguredSelectors preconfiguredSelectors(
            PreconfiguredSelectorProperties properties,
            es.jllopez.neis.domain.port.SelectorExtractionPort extractionPort) {
        return new PreconfiguredSelectors(properties, extractionPort);
    }
}
