package es.jllopez.neis.domain.port;

import es.jllopez.neis.domain.model.Address;
import es.jllopez.neis.domain.model.VerificationOutcome;

/**
 * Verification port (US-04.2, issue #22): checks that an address is
 * reachable and produces the outcome the application persists.
 * Syntactic validity (US-04.1) is checked beforehand and is NOT this
 * port's concern. NEIS's first live network call — the connector is
 * swappable behind this port.
 */
public interface AddressVerificationPort {

    /**
     * Attempts to reach the address's URL. Never throws for an
     * unreachable address: the failure IS the outcome (FAILED).
     */
    VerificationOutcome verify(Address address);
}
