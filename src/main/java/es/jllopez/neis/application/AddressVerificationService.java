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

    public AddressVerificationService(AddressCatalogService catalog,
                                      AddressVerificationPort verificationPort,
                                      UrlSyntaxValidator urlValidator) {
        this.catalog = catalog;
        this.verificationPort = verificationPort;
        this.urlValidator = urlValidator;
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
        return outcome;
    }

    /** Verifies every registered address; returns id -> outcome. */
    public List<String> verifyAll() {
        return catalog.list().stream()
                .map(a -> a.id() + "  " + verify(a.id()).status())
                .toList();
    }

    private void persist(Address address, VerificationOutcome outcome) {
        catalog.updateVerification(address.id(), outcome.status(), outcome.checkedAt());
    }
}
