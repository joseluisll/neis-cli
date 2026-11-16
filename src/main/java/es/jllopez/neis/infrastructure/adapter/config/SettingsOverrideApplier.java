package es.jllopez.neis.infrastructure.adapter.config;

import es.jllopez.neis.application.SettingsService;
import es.jllopez.neis.application.TaskWorkService;
import es.jllopez.neis.infrastructure.adapter.scrape.ScrapeConnectorRouter;
import es.jllopez.neis.infrastructure.adapter.verification.HttpClientAddressVerificationConnector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;

/**
 * Applies the persisted configuration overrides (V13, US-16.5 edit
 * path) to the consumers that read their settings at use time. Every
 * stored override is applied at boot (an override therefore survives
 * restarts and is in effect before the first user interaction of a
 * CLI run), and the management UI calls {@link #apply(String, String)}
 * right after a successful edit so the change is live immediately.
 */
@Component
public class SettingsOverrideApplier {

    private static final Logger log = LoggerFactory.getLogger(SettingsOverrideApplier.class);

    private final SettingsService settings;
    private final ScrapeConnectorRouter scrapeRouter;
    private final TaskWorkService taskWork;
    private final HttpClientAddressVerificationConnector verification;

    public SettingsOverrideApplier(SettingsService settings,
                                   ScrapeConnectorRouter scrapeRouter,
                                   TaskWorkService taskWork,
                                   HttpClientAddressVerificationConnector verification) {
        this.settings = settings;
        this.scrapeRouter = scrapeRouter;
        this.taskWork = taskWork;
        this.verification = verification;
    }

    /**
     * Boot: apply every stored override, key order stable. Bound to
     * {@link ApplicationStartedEvent} — published after the context is
     * refreshed (every bean ready) but BEFORE the application runners:
     * Spring Shell 4's runner executes the whole CLI session (every
     * command, the interactive {@code manage} screen included) inside
     * an ApplicationRunner, so an override bound to the ready event
     * would only fire at session END — after the user interaction it
     * must govern (US-16.5 review blocking finding on 227b4dc). Each
     * key is isolated: a poisoned (hand-edited) stored value is logged
     * loud and skipped, never aborting the remaining overrides.
     */
    @EventListener(ApplicationStartedEvent.class)
    public void applyStoredOverridesAtBoot() {
        Map<String, String> overrides = settings.overrides();
        if (overrides.isEmpty()) {
            return;
        }
        log.info("CONFIG: applying {} stored override(s) over the boot configuration",
                overrides.size());
        for (Map.Entry<String, String> entry : overrides.entrySet()) {
            try {
                apply(entry.getKey(), entry.getValue());
            } catch (RuntimeException e) {
                log.error("CONFIG override '{}' = '{}' could not be applied ({}), skipped",
                        entry.getKey(), entry.getValue(), e.toString());
            }
        }
    }

    /**
     * Applies one override (or its clearing, value null/blank) to the
     * consumer that owns the key. Called by the management UI right
     * after a validated save; the value re-validates here so a stored
     * value edited by hand in the database cannot poison the consumer
     * silently — an unreadable stored override is logged loud and
     * skipped, never half-applied.
     */
    public void apply(String key, String value) {
        String trimmed = value == null ? "" : value.trim();
        String refusal = settings.validate(key, trimmed).orElse(null);
        if (refusal != null) {
            log.error("CONFIG override '{}' = '{}' is invalid ({}), NOT applied", key, value, refusal);
            return;
        }
        switch (key) {
            case SettingsService.SCRAPE_CONNECTOR -> {
                if (trimmed.isEmpty()) {
                    // No override: the boot-bound mode applies again.
                    log.info("CONFIG override '{}' cleared (the boot mode applies in the "
                            + "next process; this process keeps its current mode)", key);
                } else {
                    scrapeRouter.updateMode(trimmed);
                }
            }
            case SettingsService.EXTRACTION_MAX_ITEMS ->
                    taskWork.updateMaxItems(trimmed.isEmpty() ? null : Integer.parseInt(trimmed));
            case SettingsService.VERIFICATION_TIMEOUT -> {
                if (trimmed.isEmpty()) {
                    // No override: the boot-bound timeout applies again.
                    log.info("CONFIG override '{}' cleared (the boot timeout applies in the "
                            + "next process; this process keeps its current timeout)", key);
                } else {
                    verification.updateTimeout(Duration.ofSeconds(Integer.parseInt(trimmed)));
                }
            }
            default -> log.error("CONFIG override '{}' has no applying consumer, NOT applied", key);
        }
    }
}
