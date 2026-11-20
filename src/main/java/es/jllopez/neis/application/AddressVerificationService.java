package es.jllopez.neis.application;

import es.jllopez.neis.domain.model.Address;
import es.jllopez.neis.domain.model.VerificationOutcome;
import es.jllopez.neis.domain.model.VerificationStatus;
import es.jllopez.neis.domain.port.AddressVerificationPort;
import es.jllopez.neis.domain.service.UrlSyntaxValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Use case: address verification (US-04.2, issue #22). Runs the
 * reachability check behind {@link AddressVerificationPort} and
 * persists the outcome (status + checked-at instant) onto the address
 * through the catalog. An address whose URL is syntactically invalid
 * (US-04.1) is not verified — it is marked FAILED with the reason.
 */
@Service
public class AddressVerificationService {

    private static final Logger log = LoggerFactory.getLogger(AddressVerificationService.class);

    private final AddressCatalogService catalog;
    private final AddressVerificationPort verificationPort;
    private final UrlSyntaxValidator urlValidator;
    /**
     * US-06.12 (issue #134): verification consults the ACTIVE scrape
     * configuration — an address the current mode cannot fetch
     * produces a clear warning, naming the mode and the remedy, so a
     * VERIFIED address never silently hides an un-fetchable state.
     */
    private final es.jllopez.neis.domain.port.ContentScrapePort scrape;

    /** Production constructor: Spring injects the active SCRAPE port. */
    @org.springframework.beans.factory.annotation.Autowired
    public AddressVerificationService(AddressCatalogService catalog,
                                      AddressVerificationPort verificationPort,
                                      UrlSyntaxValidator urlValidator,
                                      es.jllopez.neis.domain.port.ContentScrapePort scrape) {
        this.catalog = catalog;
        this.verificationPort = verificationPort;
        this.urlValidator = urlValidator;
        this.scrape = scrape;
    }

    /**
     * Test/back-compat convenience (US-06.12): no scrape
     * configuration — verification never emits fetchability warnings.
     * See the two-constructor trap note on the production constructor.
     */
    @SuppressWarnings("unused")
    public AddressVerificationService(AddressCatalogService catalog,
                                      AddressVerificationPort verificationPort,
                                      UrlSyntaxValidator urlValidator) {
        this(catalog, verificationPort, urlValidator, NoopScrapePort.INSTANCE);
    }

    /** Verifies one address and persists the outcome. */
    public VerificationOutcome verify(String addressId) {
        Address address = catalog.byId(addressId);
        String invalid = urlValidator.validate(address.url()).orElse(null);
        VerificationOutcome outcome;
        if (invalid != null) {
            outcome = new VerificationOutcome(VerificationStatus.FAILED,
                    java.time.Instant.now().toString(), 0,
                    "URL is syntactically invalid — " + invalid);
        } else {
            outcome = verificationPort.verify(address);
        }
        persist(address, outcome);
        log.info("Address {} verification outcome: {} ({})", address.id(),
                outcome.status(), outcome.detail());
        // US-06.12: the fetchability warning rides on every
        // verification — the catalog view must not say VERIFIED while
        // the active mode cannot fetch the address.
        scrape.fetchabilityWarning(address).ifPresent(warning ->
                log.warn("[Fetchability] verified {}: {}", address.id(),
                        warning));
        return outcome;
    }

    /** Verifies every registered address; returns id -> outcome. */
    public List<String> verifyAll() {
        return catalog.list().stream()
                .map(a -> a.id() + "  " + verify(a.id()).status())
                .toList();
    }

    private void persist(Address address, VerificationOutcome outcome) {
        catalog.updateVerification(address.id(), outcome.status(), outcome.checkedAt(),
                outcome.webpageType());
    }
}
