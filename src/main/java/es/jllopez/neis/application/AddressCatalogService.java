package es.jllopez.neis.application;

import es.jllopez.neis.domain.model.Address;
import es.jllopez.neis.domain.model.AddressType;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Use case: the static address catalog for the MVP
 * (requirement slice of US-03.1/US-03.2, issues #18/#19).
 *
 * <p>Seed set taken verbatim from the verified example addresses of
 * issue #62. Persistence and CRUD (US-03.2/US-03.3) are out of MVP
 * scope; this service is where a repository-backed implementation will
 * plug in.</p>
 */
@Service
public class AddressCatalogService {

    private final List<Address> addresses = List.of(
            new Address("ADDR-001",
                    "EU Digital Identity Wallet - News",
                    "https://ec.europa.eu/digital-building-blocks/sites/spaces/"
                            + "EUDIGITALIDENTITYWALLET/pages/712507533/News",
                    AddressType.URL_NEWS),
            new Address("ADDR-002",
                    "EU Digital Identity Wallet - Events",
                    "https://ec.europa.eu/digital-building-blocks/sites/spaces/"
                            + "EUDIGITALIDENTITYWALLET/pages/694497147/Events",
                    AddressType.URL_EVENTS),
            new Address("ADDR-003",
                    "EU Funding and Tenders Portal - Support News",
                    "https://ec.europa.eu/info/funding-tenders/opportunities/portal/"
                            + "screen/support/news?sortBy=publicationDate&order=DESC"
                            + "&pageNumber=1&pageSize=10&isExactMatch=false",
                    AddressType.URL_NEWS),
            new Address("ADDR-004",
                    "EU Funding and Tenders Portal - Support Events",
                    "https://ec.europa.eu/info/funding-tenders/opportunities/portal/"
                            + "screen/support/events?isExactMatch=false&order=DESC"
                            + "&pageNumber=1&pageSize=50&sortBy=status",
                    AddressType.URL_EVENTS));

    public List<Address> list() {
        return addresses;
    }

    public Address byId(String id) {
        return addresses.stream()
                .filter(a -> a.id().equalsIgnoreCase(id))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown address id: " + id
                                + " (known: ADDR-001..ADDR-004, seed data of issue #62)"));
    }
}
