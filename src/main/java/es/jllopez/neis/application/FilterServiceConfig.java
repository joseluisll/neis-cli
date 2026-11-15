package es.jllopez.neis.application;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Assembly configuration for the WEBSCRAPE filter slice (US-06.6,
 * issue #73): enables the {@code neis.filters} properties and exposes
 * the preconfigured-filter catalog as a bean.
 */
@Configuration
@EnableConfigurationProperties(PreconfiguredFilterProperties.class)
public class FilterServiceConfig {

    @Bean
    public PreconfiguredFilters preconfiguredFilters(
            PreconfiguredFilterProperties properties) {
        return new PreconfiguredFilters(properties);
    }
}
