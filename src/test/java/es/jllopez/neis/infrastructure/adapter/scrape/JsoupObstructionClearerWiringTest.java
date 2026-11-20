package es.jllopez.neis.infrastructure.adapter.scrape;

import es.jllopez.neis.application.ClearingServiceConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.context.properties.ConfigurationProperties;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Spring wiring of the clearer (US-06.13, issue #137): the
 * two-constructor trap — with a test-only no-arg constructor present,
 * Spring silently falls back to it UNLESS the production constructor
 * carries @Autowired (the neis-implementation lesson; only a real
 * context boot catches it, no unit test can). This runner pins that
 * the CONTEXT-built clearer receives the CONFIGURED consent policy:
 * an ACCEPT property must surface in the encounter action. */
class JsoupObstructionClearerWiringTest {

    @Test
    void theContextBuiltClearerBindsTheConfiguredConsentPolicy() {
        new ApplicationContextRunner()
                .withConfiguration(org.springframework.boot.context.annotation.UserConfigurations.of(
                        ClearingServiceConfig.class))
                .withBean(JsoupObstructionClearer.class)
                .withPropertyValues("neis.scrape.consent.policy=ACCEPT")
                .run(context -> {
                    assertTrue(context.containsBean(
                            "jsoupObstructionClearer"), "the clearer bean exists");
                    var cleared = context.getBean(JsoupObstructionClearer.class)
                            .clear("<html><body><div id='cookie-consent'>"
                                    + "We use cookies</div><main><p>Item"
                                    + "</p></main></body></html>", java.util.List.of());
                    assertTrue(
                            cleared.dialogs().get(0).action().contains("ACCEPT"),
                            "the @Autowired constructor is the one Spring "
                                    + "uses — the configured policy reached "
                                    + "the bean (a no-arg fallback would "
                                    + "record the REJECT default)");
                });
    }
}
