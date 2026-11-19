package es.jllopez.neis;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Turns SIGTERM into a CLEAN stop with JVM exit status 0 (US-15.6,
 * issue #131): the JVM's default TERM handling runs the shutdown
 * hooks and then terminates with status 143, which systemd counts as
 * {@code Failed with result 'exit-code'} — and with
 * {@code Restart=on-failure} an intentional restart schedules a
 * DUPLICATE restart (14 such events in one live day). Intercepting
 * the signal and exiting 0 makes {@code systemctl restart/stop}
 * record a clean stop.
 *
 * <p>{@code System.exit(0)} still runs the JVM shutdown hooks, so the
 * Spring context close — graceful web shutdown, the MCP session
 * closer ({@code McpSessionsShutdownLifecycle}), the executor's
 * lifecycle — proceeds exactly as before; only the FINAL exit status
 * changes. If a shutdown hook ever hangs past systemd's
 * {@code TimeoutStopSec}, systemd SIGKILLs the process as before —
 * that backstop is unchanged. Registered in SERVER mode only: a CLI
 * one-shot has no unit file and its lifecycle must stay untouched.</p>
 *
 * <p>{@code sun.misc.Signal} is JDK-internal (module
 * {@code jdk.unsupported}, exported and supported for exactly this
 * purpose). The handler never throws and never blocks: it starts the
 * clean exit.</p>
 */
@Component
@ConditionalOnProperty(prefix = "neis.server", name = "enabled",
        havingValue = "true")
public class TerminationSignalHandler {

    private static final Logger log =
            LoggerFactory.getLogger(TerminationSignalHandler.class);

    private final AtomicBoolean registered = new AtomicBoolean(false);
    private final AtomicInteger installedHooks = new AtomicInteger();
    private final Runnable exitAction;

    public TerminationSignalHandler() {
        this(TerminationSignalHandler::cleanExit);
    }

    /** Test-visible: the exit action is injected, not hardwired. */
    TerminationSignalHandler(Runnable exitAction) {
        this.exitAction = exitAction;
    }

    @PostConstruct
    void registerAtBoot() {
        register();
    }

    TerminationSignalHandler register() {
        if (registered.compareAndSet(false, true)) {
            installSignalHook();
        }
        return this;
    }

    /**
     * The JVM-global side effect, isolated so tests can drive the
     * guard logic without touching the surefire JVM's real signal
     * disposition.
     */
    void installSignalHook() {
        installedHooks.incrementAndGet();
        sun.misc.Signal.handle(new sun.misc.Signal("TERM"),
                signal -> handle(signal));
    }

    /** The signal path — factored out so tests can drive it directly. */
    void handle(sun.misc.Signal signal) {
        log.info("[SHUTDOWN] {} received — shutting down cleanly "
                + "(exit 0)", signal.getName());
        exitAction.run();
    }

    private static void cleanExit() {
        System.exit(0);
    }

    /** Visible to tests only (the registration guard). */
    int installedHooksForTest() {
        return installedHooks.get();
    }

    /** Visible to tests only (the counting seam). */
    void installedHooksForTestIncrement() {
        installedHooks.incrementAndGet();
    }

    @Override
    public String toString() {
        return "TerminationSignalHandler[registered=" + registered.get() + "]";
    }
}
