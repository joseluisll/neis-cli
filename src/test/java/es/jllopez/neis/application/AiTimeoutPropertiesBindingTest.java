package es.jllopez.neis.application;

import es.jllopez.neis.domain.model.AiOperation;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Binder-level tests for the US-13.8 timeout configuration — the
 * review finding that the flat-field design (the live finding that
 * MAP keys do not bind from environment variables) was asserted, not
 * pinned. These tests exercise the real Spring binder:
 *
 * <ul>
 *   <li>environment variables bind by relaxed binding
 *     (NEIS_AI_TIMEOUTS_DEFAULT, NEIS_AI_TIMEOUTS_SELECTOR_NEWS) — the
 *     installed service's neis.env mechanism;</li>
 *   <li>the keyword-colliding 'default' key binds through
 *     {@code @Name("default")};</li>
 *   <li>an UNKNOWN key under neis.ai.timeouts (a typo like
 *     'selector-new') FAILS the binding — fail-closed, US-08 review
 *     precedent.</li>
 * </ul>
 */
class AiTimeoutPropertiesBindingTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(AiTimeoutConfig.class);

    @Test
    void environmentVariablesBindFlatFieldsByRelaxedBinding() {
        AtomicReference<AiTimeoutProperties> captured = new AtomicReference<>();
        envVars("NEIS_AI_TIMEOUTS_DEFAULT=77s",
                "NEIS_AI_TIMEOUTS_SELECTOR_NEWS=180s").run(context -> {
                    assertThat(context).hasNotFailed();
                    captured.set(context.getBean(Sampler.class).properties);
                    assertThat(captured.get().defaultTimeout())
                            .isEqualTo(Duration.ofSeconds(77));
                    assertThat(captured.get().selectorNews())
                            .isEqualTo(Duration.ofSeconds(180));
                    // operations without an override keep the code default
                    assertThat(captured.get().forOperation(
                            AiOperation.CLASSIFICATION))
                            .isEqualTo(Duration.ofSeconds(77));
                });
    }

    @Test
    void unknownKeysFailTheBinding() {
        envVars("NEIS_AI_TIMEOUTS_SELECTOR_NEW=60s").run(context -> {
            // a typo'd operation key must fail the boot, never
            // silently fall back to the default (US-08 precedent)
            assertThat(context).hasFailed();
            Throwable failure = context.getStartupFailure();
            assertThat(String.valueOf(failure))
                    .contains("selector-new");
        });
    }

    /**
     * A REAL SystemEnvironmentPropertySource — the same property source
     * class (and the same NEIS_* name mapping) as the installed
     * service's environment. {@code withSystemProperties} would NOT
     * exercise the relaxed binding (system properties are matched
     * literally; the env-var mapping lives in the environment source).
     */
    private ApplicationContextRunner envVars(String... assignments) {
        Map<String, String> vars = new LinkedHashMap<>();
        for (String assignment : assignments) {
            int eq = assignment.indexOf('=');
            vars.put(assignment.substring(0, eq), assignment.substring(eq + 1));
        }
        return runner.withBean(Sampler.class).withInitializer(context ->
                context.getEnvironment().getPropertySources().addFirst(
                        new SystemEnvironmentPropertySource(
                                "test-neis-env", Map.copyOf(vars))));
    }

    /** Sample bean so the runner instantiates the bound properties. */
    static class Sampler {
        final AiTimeoutProperties properties;

        Sampler(AiTimeoutProperties properties) {
            this.properties = properties;
        }
    }
}
