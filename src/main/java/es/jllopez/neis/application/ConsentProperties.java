package es.jllopez.neis.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration binding of the consent-dialog policy (US-06.13, issue
 * #137): {@code neis.scrape.consent.policy} is the operator's RECORDED
 * data-use stance for consent dialogs the scraper encounters —
 * {@code ACCEPT} or {@code REJECT}, default {@code REJECT} (the
 * conservative stance: a capture is taken without granting data-use
 * consent unless the operator says otherwise).
 *
 * <p>The jsoup scrape pipeline is non-interactive: it cannot click a
 * consent button. Under BOTH policies the dialog markup is DISMISSED
 * (removed from the stored capture, never mixed into the page
 * content); the policy is what the capture provenance RECORDS — the
 * operator's declared preference, configuration not hardcode. A
 * future interactive connector (Playwright) could act the preference
 * out; the recorded stance stays the same either way.</p>
 *
 * <p>Fail-loud (US-06.15 pattern): an unknown value refuses the BOOT —
 * a typo must never silently run under an undeclared stance.</p>
 */
@ConfigurationProperties(prefix = "neis.scrape.consent")
public record ConsentProperties(String policy) {

    public static final String DEFAULT_POLICY = "REJECT";

    public ConsentProperties {
        if (policy == null || policy.isBlank()) {
            policy = DEFAULT_POLICY;
        }
        policy = policy.trim().toUpperCase(java.util.Locale.ROOT);
        if (!policy.equals("ACCEPT") && !policy.equals("REJECT")) {
            throw new IllegalArgumentException(
                    "neis.scrape.consent.policy: unknown value '"
                            + policy + "' — use ACCEPT or REJECT "
                            + "(US-06.13: an unknown consent policy must "
                            + "refuse the boot, never run undeclared)");
        }
    }

    /** True when the recorded stance is ACCEPT. */
    public boolean isAccept() {
        return "ACCEPT".equals(policy);
    }
}
