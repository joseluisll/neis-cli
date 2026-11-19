package es.jllopez.neis;

import io.modelcontextprotocol.spec.McpStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpStreamableServerSession;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * US-15.6 (issue #131): the MCP session closer must run at a phase
 * ABOVE the web server's graceful-shutdown lifecycle (which holds the
 * context close waiting on "active" SSE requests), and must never
 * block the shutdown past its own bounded timeout.
 */
class McpSessionsShutdownLifecycleTest {

    private McpStreamableServerTransportProvider provider(
            AtomicInteger closes) {
        return new McpStreamableServerTransportProvider() {
            @Override
            public void setSessionFactory(
                    McpStreamableServerSession.Factory factory) {
            }

            @Override
            public Mono<Void> notifyClients(String method, Object params) {
                return Mono.empty();
            }

            @Override
            public Mono<Void> closeGracefully() {
                closes.incrementAndGet();
                return Mono.empty();
            }
        };
    }

    @Test
    void stopClosesTheTransportSessions() {
        AtomicInteger closes = new AtomicInteger();
        McpStreamableServerTransportProvider provider = provider(closes);
        McpSessionsShutdownLifecycle lifecycle =
                new McpSessionsShutdownLifecycle(
                        new StubProvider<>(provider));

        lifecycle.stop();

        assertEquals(1, closes.get(),
                "stop() must close the MCP transport sessions");
    }

    @Test
    void stopWithoutATransportProviderIsANoOp() {
        McpSessionsShutdownLifecycle lifecycle =
                new McpSessionsShutdownLifecycle(
                        new StubProvider<>(null));

        lifecycle.stop(); // must not throw
    }

    @Test
    void phaseIsAboveTheWebServerGracefulShutdownPhase() {
        McpSessionsShutdownLifecycle lifecycle =
                new McpSessionsShutdownLifecycle(
                        new StubProvider<>(null));

        // Boot 4.1's WebServerGracefulShutdownLifecycle phase (verified
        // from the bytecode) — our stop must run BEFORE it
        assertEquals(2147482624, lifecycle.getPhase());
        assertTrue(lifecycle.getPhase() > 2147482623,
                "the MCP session closer must stop before the web "
                        + "server's graceful shutdown counts requests");
    }

    /** ObjectProvider is not functional — the minimal test stub. */
    private static final class StubProvider<T>
            implements org.springframework.beans.factory.ObjectProvider<T> {
        private final T instance;

        StubProvider(T instance) {
            this.instance = instance;
        }

        @Override
        public T getObject() {
            return instance;
        }

        @Override
        public T getObject(Object... args) {
            return instance;
        }

        @Override
        public T getIfAvailable() {
            return instance;
        }

        @Override
        public T getIfUnique() {
            return instance;
        }
    }
}
