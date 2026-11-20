package es.jllopez.neis.application;

import es.jllopez.neis.domain.model.Address;
import es.jllopez.neis.domain.model.ScrapeResult;
import es.jllopez.neis.domain.port.ContentScrapePort;

/**
 * A SCRAPE port that fetches nothing and warns about nothing
 * (US-06.12, issue #134): the test/back-compat convenience
 * constructors of the catalog and verification services delegate to
 * this — a service built without a scrape configuration simply never
 * emits fetchability warnings. Only {@link #fetchabilityWarning} is
 * ever consulted by those services; the fetch itself throws loud if
 * anything ever calls it (it must not).
 */
final class NoopScrapePort implements ContentScrapePort {

    static final NoopScrapePort INSTANCE = new NoopScrapePort();

    private NoopScrapePort() {
    }

    @Override
    public ScrapeResult scrape(Address address) {
        throw new UnsupportedOperationException(
                "NoopScrapePort fetches nothing (US-06.12 test/"
                        + "back-compat constructor) — address "
                        + address.id());
    }
}
