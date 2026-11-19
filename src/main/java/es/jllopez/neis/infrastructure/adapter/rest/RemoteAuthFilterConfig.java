package es.jllopez.neis.infrastructure.adapter.rest;

import es.jllopez.neis.infrastructure.adapter.ConditionalOnServerEnabled;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * The remote-adapter security seam (US-18.3, issue #118): EVERY remote
 * request passes through this ONE filter. It is a no-op today — the
 * MCP endpoint is unauthenticated for now (owner decision
 * 2026-11-19) — and it is the single documented place where an
 * authentication layer attaches later: one filter, both surfaces
 * (REST and MCP ride the same servlet chain), nothing else to
 * re-plumb.
 *
 * <p>Registered only in server mode (the remote adapters do not exist
 * otherwise, so CLI runs never gain servlet machinery).</p>
 */
@Configuration
@ConditionalOnServerEnabled
public class RemoteAuthFilterConfig {

    @Bean
    public FilterRegistrationBean<Filter> remoteAuthFilter() {
        FilterRegistrationBean<Filter> registration =
                new FilterRegistrationBean<>((request, response, chain) -> {
                    // NO-OP today: the unauthenticated interim state
                    // (owner decision 2026-11-19). Authentication lands
                    // HERE and NOWHERE ELSE when US-XX adds it.
                    chain.doFilter(request, response);
                });
        registration.addUrlPatterns("/api/*", "/mcp/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registration.setName("remoteAuthFilter");
        return registration;
    }

    /** Marker type so the javadoc contract stays greppable. */
    interface AuthAttachPoint {
        // the future token check replaces the no-op lambda above
    }
}
