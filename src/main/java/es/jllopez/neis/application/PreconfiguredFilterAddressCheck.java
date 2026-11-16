package es.jllopez.neis.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Referential check of the preconfigured WEBSCRAPE filters (US-06.6,
 * issue #73; US-06.10 review finding): every configured entry names an
 * address id, and a TYPO'D id used to boot fine and stay dead forever —
 * {@link PreconfiguredFilters#find} matches by id, so a dangling entry
 * never matched any run and never surfaced anywhere.
 *
 * <p>The check cannot live in {@link PreconfiguredFilterProperties}
 * (configuration-time validation is syntax-only: the beans bind before
 * the database and its catalog exist). It runs on
 * {@link ApplicationStartedEvent} — the context is refreshed (the
 * database migrated and the catalog readable) but the application
 * runners have not started, so the verdict lands BEFORE the first
 * command or the interactive session, the same timing rule as
 * {@code SettingsOverrideApplier}. A dangling id FAILS the boot loud
 * (the same rule as an unknown scrape-connector mode or an unknown
 * webpage-type strategy): a filter configured for an address that does
 * not exist is always a config mistake, and a silent dead entry is the
 * worst way to find out.</p>
 */
@Component
public class PreconfiguredFilterAddressCheck {

    private static final Logger log =
            LoggerFactory.getLogger(PreconfiguredFilterAddressCheck.class);

    private final PreconfiguredFilterProperties properties;
    private final AddressCatalogService catalog;

    public PreconfiguredFilterAddressCheck(PreconfiguredFilterProperties properties,
                                           AddressCatalogService catalog) {
        this.properties = properties;
        this.catalog = catalog;
    }

    /**
     * Fails the boot when a configured entry names an address the
     * catalog does not know; names every dangling id and the filter
     * each carries, and the remedy (fix the id or register the
     * address).
     */
    @EventListener(ApplicationStartedEvent.class)
    public void checkConfiguredAddressesExist() {
        if (properties.preconfigured().isEmpty()) {
            return;
        }
        Set<String> dangling = new LinkedHashSet<>();
        for (PreconfiguredFilterProperties.Entry entry : properties.preconfigured()) {
            try {
                catalog.byId(entry.address());
            } catch (IllegalArgumentException e) {
                // byId's unknown-id refusal; any other failure (a port
                // error surfacing as DataAccessException) propagates
                // unwrapped instead of being mislabeled 'unknown id'.
                dangling.add(entry.address() + " (filter '" + entry.name() + "')");
            }
        }
        if (!dangling.isEmpty()) {
            throw new IllegalStateException("neis.filters.preconfigured: "
                    + dangling.size() + " configured filter(s) name unknown address "
                    + "id(s): " + dangling + " — fix the id(s) or register the "
                    + "address(es) with 'address add' (US-06.10: a dangling id "
                    + "never applies and never surfaced)");
        }
        log.info("CONFIG: {} preconfigured filter(s), every address id exists",
                properties.preconfigured().size());
    }
}
