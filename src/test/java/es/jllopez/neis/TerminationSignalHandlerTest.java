package es.jllopez.neis;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * US-15.6 (issue #131): SIGTERM must produce a CLEAN stop (exit 0).
 * The signal path is factored so the tests drive it directly — a real
 * {@code Signal.raise} inside the surefire JVM would race whatever
 * else has handlers registered there, and {@code installSignalHook}
 * is overridden to keep the JVM-global side effect out of the tests.
 */
class TerminationSignalHandlerTest {

    @Test
    void termSignalRunsTheCleanExitAction() {
        AtomicBoolean exited = new AtomicBoolean(false);
        TerminationSignalHandler handler =
                new TerminationSignalHandler(() -> exited.set(true));

        handler.handle(new sun.misc.Signal("TERM"));

        assertTrue(exited.get(), "SIGTERM must run the exit action");
    }

    @Test
    void registrationIsIdempotent_exactlyOneSignalHook() {
        TerminationSignalHandler handler =
                new TerminationSignalHandler(() -> { }) {
                    @Override
                    void installSignalHook() {
                        // count only — never touch the real JVM handler
                        super.installedHooksForTestIncrement();
                    }
                };

        handler.register();
        handler.register();
        handler.register();

        assertEquals(1, handler.installedHooksForTest(),
                "the signal hook must be installed exactly once");
    }
}
