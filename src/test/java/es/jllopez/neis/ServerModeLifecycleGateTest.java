package es.jllopez.neis;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * US-15.6 review follow-up (N1): both server-mode lifecycle beans are
 * gated with {@code neis.server.enabled=true} — the signal handler
 * must NEVER register in a CLI one-shot boot (it would replace the
 * surefire JVM's TERM disposition, and in production it would change
 * CLI behavior). Pinned via the annotation METADATA: a
 * context-runner test here would CREATE the handler bean and install
 * the real signal hook in the test JVM — exactly the side effect this
 * guard exists to prevent. The gate mechanics themselves are already
 * pinned by {@code JobStartupReconciliationGateTest} (same pattern,
 * side-effect-free bean).
 */
class ServerModeLifecycleGateTest {

    @Test
    void bothLifecycleBeansCarryTheServerModeGate() {
        for (Class<?> type : List.of(TerminationSignalHandler.class,
                McpSessionsShutdownLifecycle.class)) {
            ConditionalOnProperty gate = Annotated.find(type);
            assertNotNull(gate, type.getSimpleName()
                    + " lost @ConditionalOnProperty");
            assertEquals("neis.server", gate.prefix(),
                    type.getSimpleName());
            assertArrayEquals(new String[]{"enabled"}, gate.name(),
                    type.getSimpleName());
            assertEquals("true", gate.havingValue(),
                    type.getSimpleName());
            assertFalse(gate.matchIfMissing(),
                    type.getSimpleName() + " must default to ABSENT in "
                            + "CLI mode (matchIfMissing=false)");
        }
    }

    /** Tiny indirection so the test reads top-down. */
    private static final class Annotated {
        static ConditionalOnProperty find(Class<?> type) {
            return org.springframework.core.annotation.AnnotatedElementUtils
                    .findMergedAnnotation(type, ConditionalOnProperty.class);
        }
    }
}
