package es.jllopez.neis.application;

import es.jllopez.neis.domain.model.Address;
import es.jllopez.neis.domain.model.AddressType;
import es.jllopez.neis.domain.model.VerificationOutcome;
import es.jllopez.neis.domain.model.VerificationStatus;
import es.jllopez.neis.domain.port.AddressVerificationPort;
import es.jllopez.neis.domain.service.UrlSyntaxValidator;
import es.jllopez.neis.testsupport.InMemoryAddressCatalogPort;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The verification use case (US-04.2, issue #22): the outcome of the
 * verification port is persisted onto the address; syntactically
 * invalid addresses are FAILED without a network call.
 */
class AddressVerificationServiceTest {

    @Test
    void theVerificationOutcomeIsPersistedOntoTheAddress() {
        InMemoryAddressCatalogPort port = new InMemoryAddressCatalogPort(
                List.of(new Address("ADDR-001", "Source", "https://example.eu/news",
                        AddressType.URL_NEWS)));
        AddressCatalogService catalog = new AddressCatalogService(port,
                new UrlSyntaxValidator());
        AddressVerificationService service = new AddressVerificationService(catalog,
                address -> new VerificationOutcome(VerificationStatus.VERIFIED,
                        "2026-11-11T12:00:00Z", 200, "reachable (HTTP 200)"),
                new UrlSyntaxValidator());

        VerificationOutcome outcome = service.verify("ADDR-001");

        assertEquals(VerificationStatus.VERIFIED, outcome.status());
        Address reloaded = catalog.byId("ADDR-001");
        assertEquals(VerificationStatus.VERIFIED, reloaded.verificationStatus());
        assertEquals("2026-11-11T12:00:00Z", reloaded.verifiedAt());
        assertTrue(reloaded.isVerified());
        // The other fields survive the verification write.
        assertEquals("https://example.eu/news", reloaded.url());
    }

    @Test
    void aFailedCheckIsAlsoPersisted() {
        InMemoryAddressCatalogPort port = new InMemoryAddressCatalogPort(
                List.of(new Address("ADDR-001", "Source", "https://example.eu/news",
                        AddressType.URL_NEWS)));
        AddressCatalogService catalog = new AddressCatalogService(port,
                new UrlSyntaxValidator());
        AddressVerificationService service = new AddressVerificationService(catalog,
                address -> new VerificationOutcome(VerificationStatus.FAILED,
                        "2026-11-11T12:00:00Z", 0, "ConnectException"),
                new UrlSyntaxValidator());

        VerificationOutcome outcome = service.verify("ADDR-001");

        assertEquals(VerificationStatus.FAILED, outcome.status());
        assertEquals(VerificationStatus.FAILED,
                catalog.byId("ADDR-001").verificationStatus());
    }

    @Test
    void aSyntacticallyInvalidUrlIsFailedWithoutCallingThePort() {
        InMemoryAddressCatalogPort port = new InMemoryAddressCatalogPort(
                List.of(new Address("ADDR-001", "Bad", "https://exa mple.eu",
                        AddressType.URL_NEWS)));
        AddressCatalogService catalog = new AddressCatalogService(port,
                new UrlSyntaxValidator());
        AtomicInteger calls = new AtomicInteger();
        AddressVerificationService service = new AddressVerificationService(catalog,
                address -> {
                    calls.incrementAndGet();
                    return new VerificationOutcome(VerificationStatus.VERIFIED,
                            "2026-11-11T12:00:00Z", 200, "should not happen");
                },
                new UrlSyntaxValidator());

        VerificationOutcome outcome = service.verify("ADDR-001");

        assertEquals(VerificationStatus.FAILED, outcome.status());
        assertEquals(0, calls.get(), "the verification port must not be called");
        assertEquals(VerificationStatus.FAILED,
                catalog.byId("ADDR-001").verificationStatus());
        assertNotNull(outcome.detail());
    }

    @Test
    void verifyAllReportsEveryRegisteredAddress() {
        InMemoryAddressCatalogPort port = new InMemoryAddressCatalogPort(List.of(
                new Address("ADDR-001", "A", "https://example.eu/a", AddressType.URL_NEWS),
                new Address("ADDR-002", "B", "https://example.eu/b", AddressType.URL_EVENTS)));
        AddressCatalogService catalog = new AddressCatalogService(port,
                new UrlSyntaxValidator());
        AddressVerificationService service = new AddressVerificationService(catalog,
                address -> new VerificationOutcome(VerificationStatus.VERIFIED,
                        "2026-11-11T12:00:00Z", 200, "ok"),
                new UrlSyntaxValidator());

        List<String> lines = service.verifyAll();

        assertEquals(2, lines.size());
        assertTrue(lines.get(0).contains("ADDR-001"));
        assertTrue(lines.get(0).contains("VERIFIED"));
        assertTrue(lines.get(1).contains("ADDR-002"));
    }
}
