package es.jllopez.neis.application;

import es.jllopez.neis.domain.model.Address;
import es.jllopez.neis.domain.model.AddressType;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Use case: the static address catalog for the MVP
 * (requirement slice of US-03.1/US-03.2, issues #18/#19).
 *
 * <p>Seed set taken from the verified example addresses of issue #62
 * (extended with the HaDEA, EMA and Aptitude sources). Every address
 * carries a free text description (part of the register, US-03.1).
 * Persistence and CRUD (US-03.2/US-03.3) are out of MVP scope; this
 * service is where a repository-backed implementation will plug in.</p>
 */
@Service
public class AddressCatalogService {

    private final List<Address> addresses = List.of(
            new Address("ADDR-001",
                    "EU Digital Identity Wallet - News",
                    "News page of the EU Digital Identity Wallet initiative "
                            + "(Confluence space of the Digital Building Blocks). "
                            + "Lists project news with dates and links.",
                    "https://ec.europa.eu/digital-building-blocks/sites/spaces/"
                            + "EUDIGITALIDENTITYWALLET/pages/712507533/News",
                    AddressType.URL_NEWS),
            new Address("ADDR-002",
                    "EU Digital Identity Wallet - Events",
                    "Events page of the EU Digital Identity Wallet initiative "
                            + "(Confluence space of the Digital Building Blocks). "
                            + "Lists workshops, webinars and meetings.",
                    "https://ec.europa.eu/digital-building-blocks/sites/spaces/"
                            + "EUDIGITALIDENTITYWALLET/pages/694497147/Events",
                    AddressType.URL_EVENTS),
            new Address("ADDR-003",
                    "EU Funding and Tenders Portal - Support News",
                    "Support news of the EU Funding and Tenders Portal (Angular "
                            + "SPA; the list is client-rendered). One page of the "
                            + "latest entries.",
                    "https://ec.europa.eu/info/funding-tenders/opportunities/portal/"
                            + "screen/support/news?sortBy=publicationDate&order=DESC"
                            + "&pageNumber=1&pageSize=10&isExactMatch=false",
                    AddressType.URL_NEWS),
            new Address("ADDR-004",
                    "EU Funding and Tenders Portal - Support Events",
                    "Support events of the EU Funding and Tenders Portal (Angular "
                            + "SPA; the list is client-rendered). One page of the "
                            + "latest entries.",
                    "https://ec.europa.eu/info/funding-tenders/opportunities/portal/"
                            + "screen/support/events?isExactMatch=false&order=DESC"
                            + "&pageNumber=1&pageSize=50&sortBy=status",
                    AddressType.URL_EVENTS),
            new Address("ADDR-005",
                    "HaDEA - Events",
                    "Events organised or funded by the European Health and Digital "
                            + "Executive Agency (HaDEA), a Drupal OpenEuropa listing. "
                            + "Paginated (?page=N) with a facet filter form: event "
                            + "status, type, date range, topics, programmes.",
                    "https://hadea.ec.europa.eu/events_en",
                    AddressType.URL_EVENTS),
            new Address("ADDR-006",
                    "HaDEA - News",
                    "News of the European Health and Digital Executive Agency "
                            + "(HaDEA), a Drupal OpenEuropa listing. 100 entries per "
                            + "page over ~9 pages, with topic and programme filters.",
                    "https://hadea.ec.europa.eu/news_en",
                    AddressType.URL_NEWS),
            new Address("ADDR-007",
                    "EMA - News",
                    "News of the European Medicines Agency (EMA), a Drupal "
                            + "listing. 10 news teasers per page over a deep archive "
                            + "(hundreds of pages), with keyword, date, topic, "
                            + "disease-area and responsible-body filters.",
                    "https://www.ema.europa.eu/en/news",
                    AddressType.URL_NEWS),
            new Address("ADDR-008",
                    "EMA - Upcoming Events",
                    "Upcoming events of the European Medicines Agency (EMA): "
                            + "committee meetings, workshops and training courses. "
                            + "Paginated (?page=N) with topic, category and "
                            + "responsible-body filter selects.",
                    "https://www.ema.europa.eu/en/events/upcoming-events",
                    AddressType.URL_EVENTS),
            new Address("ADDR-009",
                    "EUDI Wallet Aptitude - Newsfeed",
                    "Newsfeed of Aptitude, the European Digital Identity Wallet "
                            + "competence site. 12 news per page over 6 pages, "
                            + "paginated (?pages=N); no filter controls.",
                    "https://aptitude.digital-identity-wallet.eu/news-corner/newsfeed",
                    AddressType.URL_NEWS));

    public List<Address> list() {
        return addresses;
    }

    public Address byId(String id) {
        return addresses.stream()
                .filter(a -> a.id().equalsIgnoreCase(id))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown address id: " + id
                                + " (known: ADDR-001..ADDR-009, seed data of issue #62)"));
    }
}
