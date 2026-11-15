package es.jllopez.neis.application;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Assembly configuration for the WEBSCRAPE filter slice (US-06.6,
 * issue #73): enables the {@code neis.filters} properties. The
 * preconfigured-filter catalog needs NO bean method here (US-06.6
 * review finding: the old explicit registration duplicated the
 * {@link PreconfiguredFilters @Component} — Spring registered the
 * catalog twice): component scanning picks it up.
 */
@Configuration
@EnableConfigurationProperties(PreconfiguredFilterProperties.class)
public class FilterServiceConfig {
}
