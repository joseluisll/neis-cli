package es.jllopez.neis;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.boot.web.server.servlet.ConfigurableServletWebServerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Optional;

/**
 * Server run mode (US-18.6, issue #121): validates at boot that the
 * remote-adapter gate and the web stack AGREE.
 *
 * <p>{@code neis.server.enabled=true} only means something with a
 * servlet web application type (that is how server.sh boots it).
 * The inverse mismatch is allowed but useless (web on, no adapters);
 * the dangerous one is the first: adapters "enabled" with no web
 * server to serve them would boot silently dead. The two properties
 * are set together by server.sh; a hand-rolled override that gets
 * only one of them fails the boot with a message naming the fix.</p>
 */
@Component
public class ServerModeConfig {

    private static final Logger log = LoggerFactory.getLogger(ServerModeConfig.class);

    private final Environment environment;

    public ServerModeConfig(Environment environment) {
        this.environment = environment;
    }

    @PostConstruct
    void validate() {
        boolean enabled = environment.getProperty("neis.server.enabled",
                Boolean.class, false);
        String webType = environment.getProperty("spring.main.web-application-type",
                "none");
        boolean mcpEnabled = environment.getProperty(
                "spring.ai.mcp.server.enabled", Boolean.class, false);
        String problem = validate(enabled, webType).or(
                () -> validateMcpGate(enabled, mcpEnabled)).orElse(null);
        if (problem != null) {
            throw new IllegalStateException(problem);
        }
        if (enabled) {
            String bind = environment.getProperty("neis.server.bind", "127.0.0.1");
            String port = environment.getProperty("neis.server.port", "8080");
            log.info("[SERVER] remote adapters enabled — binding {}:{} "
                            + "(UNAUTHENTICATED; remote reachability requires an "
                            + "explicit neis.server.bind change)", bind, port);
        }
    }

    /**
     * The mode-combination invariant, pure for testability:
     * adapters enabled REQUIRES a servlet web application type.
     */
    static Optional<String> validate(boolean serverEnabled, String webApplicationType) {
        if (serverEnabled && !"servlet".equals(webApplicationType)) {
            return Optional.of("neis.server.enabled=true requires the web layer: "
                    + "boot server mode with --spring.main.web-application-type=servlet "
                    + "(server.sh does this), or set neis.server.enabled=false. "
                    + "Current web application type: "
                    + webApplicationType + ". Nothing started.");
        }
        return Optional.empty();
    }

    /**
     * The MCP gate (US-18.3 review finding) must NOT be turned on
     * directly: the yml placeholder tracks {@code neis.server.enabled}
     * one way, and a direct {@code spring.ai.mcp.server.enabled=true}
     * override would boot the /mcp endpoint OUTSIDE the documented
     * server mode — without the RemoteAuthFilter attach point and
     * without the server-mode boot log. Fail the boot on the
     * disagreement; server mode is the only way to expose MCP.
     */
    static Optional<String> validateMcpGate(boolean serverEnabled,
                                            boolean mcpEnabled) {
        if (mcpEnabled && !serverEnabled) {
            return Optional.of("spring.ai.mcp.server.enabled=true without "
                    + "neis.server.enabled=true would expose the /mcp "
                    + "endpoint outside server mode (no RemoteAuthFilter "
                    + "attach point). Enable server mode instead "
                    + "(server.sh sets the property pair). Nothing started.");
        }
        return Optional.empty();
    }

    /**
     * Server mode must NOT execute commands on boot: with the shell
     * runners in place, Spring Shell's ApplicationRunner would execute
     * the (empty) argv as a command and kill the boot with
     * CommandNotFoundException (found live). The stock runner bean is
     * registered under this exact name and backs off on a namesake
     * (@ConditionalOnMissingBean) — so server mode overrides it with a
     * no-op and the boot serves HTTP instead of running a command.
     * CLI mode never sets neis.server.enabled, keeping the stock shell.
     */
    @Bean
    @ConditionalOnProperty(prefix = "neis.server", name = "enabled",
            havingValue = "true")
    ApplicationRunner springShellApplicationRunner() {
        return args -> { };
    }

    /**
     * Applies the server bind/port OURSELVES. Boot 4.1's stock
     * ServletWebServerFactoryCustomizer no longer maps
     * {@code server.address} onto the factory (verified in the
     * bytecode: getAddress is never called — found live, the server
     * came up on {@code *:8080} with the property set), so
     * {@code neis.server.bind}/{@code neis.server.port} are the single
     * source of truth and this customizer is the only applier. Only
     * active in server mode (a CLI run never builds a web factory).
     */
    @Bean
    WebServerFactoryCustomizer<ConfigurableServletWebServerFactory>
            serverBindCustomizer(Environment environment) {
        return factory -> {
            boolean enabled = environment.getProperty("neis.server.enabled",
                    Boolean.class, false);
            if (!enabled) {
                return;
            }
            String bind = environment.getProperty("neis.server.bind", "127.0.0.1");
            int port = environment.getProperty("neis.server.port", Integer.class, 8080);
            try {
                factory.setAddress(InetAddress.getByName(bind));
                factory.setPort(port);
                log.info("[SERVER] web server bound to {}:{}", bind, port);
            } catch (UnknownHostException e) {
                throw new IllegalStateException("Invalid neis.server.bind: '"
                        + bind + "' — " + e.getMessage(), e);
            }
        };
    }
}
