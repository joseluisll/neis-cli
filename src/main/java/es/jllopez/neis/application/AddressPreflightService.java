package es.jllopez.neis.application;

import es.jllopez.neis.domain.port.AddressPreflightPort;
import org.springframework.stereotype.Service;

/**
 * Use case: the address preflight capture (US-13.6, issue #65).
 * Probes an address's entry page (lightweight static fetch, rendered
 * probe on an application shell), classifies it into the webpage-type
 * taxonomy (US-06.5) and estimates the retrieval connector — then
 * persists the outcome as address metadata: the classification goes to
 * the address (the executor consults it before scheduling WEBSCRAPE
 * runs) and the full probe record to the capture metadata (US-11.4,
 * V18 {@code captures.preflight} column).
 *
 * <p>The estimate is a suggestion the user can override ('address
 * webpage-type', the per-run connector parameter) — never a silent
 * reconfiguration. The AI (or deterministic heuristics as fallback,
 * which is what the deterministic classifier is) performs the
 * estimation through the provider-agnostic integration point.</p>
 */
@Service
public class AddressPreflightService {

    private final AddressCatalogService catalog;
    private final ContentStoreService contentStore;
    private final AddressPreflightPort preflightPort;

    public AddressPreflightService(AddressCatalogService catalog,
                                   ContentStoreService contentStore,
                                   AddressPreflightPort preflightPort) {
        this.catalog = catalog;
        this.contentStore = contentStore;
        this.preflightPort = preflightPort;
    }

    /**
     * Probes the address, persists the classification onto the address
     * (only when the probe produced one — a failed classification
     * never wipes a stored type) and the probe record onto the capture
     * metadata. Returns the probe outcome.
     */
    public AddressPreflightPort.Outcome preflight(String addressId) {
        es.jllopez.neis.domain.model.Address address = catalog.byId(addressId);
        AddressPreflightPort.Outcome outcome = preflightPort.preflight(address);
        if (outcome.webpageType() != null) {
            catalog.updateWebpageType(address.id(), outcome.webpageType());
        }
        contentStore.recordPreflight(address, address.url(), outcome.preflightJson());
        return outcome;
    }
}
