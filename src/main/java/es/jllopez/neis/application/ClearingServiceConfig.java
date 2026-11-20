package es.jllopez.neis.application;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Assembly configuration for the obstruction-clearing slice (US-06.7,
 * issue #75): enables the {@code neis.scrape.clearing} properties.
 * The {@link ClearingSelectors @Component} catalog is picked up by
 * component scanning (no bean method here — the US-06.6 lesson: an
 * explicit registration duplicates the scanned component).
 */
@Configuration
@EnableConfigurationProperties({ClearingProperties.class,
        ConsentProperties.class})
public class ClearingServiceConfig {
}
