package es.jllopez.neis.application;

import es.jllopez.neis.domain.service.PaginationDetector;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Assembly configuration: exposes domain services as beans. The domain
 * stays framework-free (US-01.4 rule) — this configuration owns the
 * Spring wiring for its pure-Java services.
 */
@Configuration
public class DomainServiceConfig {

    @Bean
    public PaginationDetector paginationDetector() {
        return new PaginationDetector();
    }
}
