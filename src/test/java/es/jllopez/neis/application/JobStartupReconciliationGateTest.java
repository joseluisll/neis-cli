package es.jllopez.neis.application;

import es.jllopez.neis.testsupport.InMemoryExecutionStorePort;
import es.jllopez.neis.testsupport.InMemoryJobStorePort;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.annotation.UserConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * US-18.10 review follow-up: the reconciler is gated to SERVER mode
 * (review BLOCK B1 — a CLI one-shot shares the live service's SQLite
 * file and must never finalize its in-flight records). Pinned at the
 * wiring level against future regressions: the bean exists only when
 * neis.server.enabled=true.
 */
class JobStartupReconciliationGateTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean("jobStore", InMemoryJobStorePort.class)
            .withBean("executionStore", InMemoryExecutionStorePort.class,
                    () -> new InMemoryExecutionStorePort(List.of("WEBSCRAPE")))
            .withConfiguration(UserConfigurations.of(
                    JobStartupReconciliation.class));

    @Test
    void reconcilerAbsentInCliMode() {
        runner.run(context ->
                assertThat(context).doesNotHaveBean(JobStartupReconciliation.class));
    }

    @Test
    void reconcilerPresentInServerMode() {
        runner.withPropertyValues("neis.server.enabled=true").run(context ->
                assertThat(context).hasSingleBean(JobStartupReconciliation.class));
    }
}
