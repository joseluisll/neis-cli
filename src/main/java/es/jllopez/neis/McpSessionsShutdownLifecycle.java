package es.jllopez.neis;

import io.modelcontextprotocol.spec.McpStreamableServerTransportProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Closes open MCP Streamable-HTTP sessions BEFORE the web server's
 * graceful-shutdown phase waits on active requests (US-15.6, issue
 * #131): an idle-but-open MCP SSE stream is an "active request" to
 * {@code webServerGracefulShutdown}, which then stalls for its whole
 * 30 s budget and aborts ("Graceful shutdown aborted with one or more
 * requests still active") — found live on every service restart.
 *
 * <p>Lifecycle ordering is the whole fix: SmartLifecycle beans stop in
 * DESCENDING phase order, and Boot 4.1's
 * {@code WebServerGracefulShutdownLifecycle} runs at phase
 * {@code 2147482623} ({@code Integer.MAX_VALUE - 512}, verified from
 * the bytecode). This component stops one step ABOVE it, so the MCP
 * sessions are already closed when the web server counts active
 * requests.</p>
 */
@Component
@ConditionalOnProperty(prefix = "neis.server", name = "enabled",
        havingValue = "true")
public class McpSessionsShutdownLifecycle implements SmartLifecycle {

    /**
     * One above Boot 4.1's {@code WebServerGracefulShutdownLifecycle}
     * phase (2147482623) — see class javadoc.
     */
    public static final int MCP_SESSIONS_SHUTDOWN_PHASE = 2147482624;

    private static final Logger log =
            LoggerFactory.getLogger(McpSessionsShutdownLifecycle.class);

    /** Bounded: the context's own 30 s phase budget must never be hit. */
    private static final Duration CLOSE_TIMEOUT = Duration.ofSeconds(5);

    private final ObjectProvider<McpStreamableServerTransportProvider>
            transportProvider;

    private volatile boolean running;

    public McpSessionsShutdownLifecycle(
            ObjectProvider<McpStreamableServerTransportProvider>
                    transportProvider) {
        this.transportProvider = transportProvider;
    }

    @Override
    public int getPhase() {
        return MCP_SESSIONS_SHUTDOWN_PHASE;
    }

    @Override
    public void start() {
        running = true;
    }

    @Override
    public void stop() {
        running = false;
        McpStreamableServerTransportProvider provider =
                transportProvider.getIfAvailable();
        if (provider == null) {
            return;
        }
        try {
            provider.closeGracefully().block(CLOSE_TIMEOUT);
            log.info("[SHUTDOWN] MCP transport sessions closed");
        } catch (RuntimeException e) {
            // never block the shutdown on a session that refuses to
            // close — the web server's own budget is the backstop
            log.warn("[SHUTDOWN] closing MCP transport sessions did not "
                    + "complete within {}: {}", CLOSE_TIMEOUT, e.toString());
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
