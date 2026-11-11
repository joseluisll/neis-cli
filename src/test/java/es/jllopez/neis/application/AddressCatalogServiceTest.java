package es.jllopez.neis.application;

import es.jllopez.neis.domain.model.Address;
import es.jllopez.neis.domain.model.AddressType;
import es.jllopez.neis.domain.model.VerificationStatus;
import es.jllopez.neis.domain.service.UrlSyntaxValidator;
import es.jllopez.neis.testsupport.InMemoryAddressCatalogPort;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The registered address catalog over the repository port
 * (US-03.1/#18, US-03.2/#19): create, list, edit and remove through
 * the use case, id auto-assignment and duplicate refusal. The port is
 * the in-memory test double; the SQLite-backed connector is exercised
 * by {@code AddressCatalogPersistenceTest}.
 */
class AddressCatalogServiceTest {

    private final AddressCatalogService catalog =
            new AddressCatalogService(new InMemoryAddressCatalogPort(),
                    new UrlSyntaxValidator());

    @Test
    void createAssignsTheNextFreeAddrNnnIdWhenOmitted() {
        Address a = catalog.create(null, "Source A", "desc", "https://example.eu/a",
                AddressType.URL_NEWS);
        assertEquals("ADDR-001", a.id());

        Address b = catalog.create("ADDR-005", "Source B", null, "https://example.eu/b",
                AddressType.URL_EVENTS);
        assertEquals("ADDR-005", b.id());

        assertEquals("ADDR-006", catalog.nextAddressId().orElseThrow());
    }

    @Test
    void createRefusesAnAlreadyRegisteredId() {
        catalog.create("ADDR-001", "Source A", null, "https://example.eu/a",
                AddressType.URL_NEWS);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> catalog.create("addr-001", "Again", null, "https://example.eu/z",
                        AddressType.URL_NEWS));
        assertTrue(e.getMessage().contains("already registered"));
    }

    @Test
    void updateKeepsEveryFieldNotPassed() {
        catalog.create("ADDR-001", "Source A", "original description",
                "https://example.eu/a", AddressType.URL_NEWS);

        Address edited = catalog.update("ADDR-001", null, null,
                "https://example.eu/a2", null);

        assertEquals("Source A", edited.name());
        assertEquals("original description", edited.description());
        assertEquals("https://example.eu/a2", edited.url());
        assertEquals(AddressType.URL_NEWS, edited.type());
    }

    @Test
    void updatePreservesTheVerificationState() {
        catalog.create("ADDR-001", "Source A", null, "https://example.eu/a",
                AddressType.URL_NEWS);
        catalog.updateVerification("ADDR-001", VerificationStatus.VERIFIED,
                "2026-11-11T12:00:00Z");

        Address edited = catalog.update("ADDR-001", "Renamed", null, null, null);

        assertEquals(VerificationStatus.VERIFIED, edited.verificationStatus());
        assertEquals("2026-11-11T12:00:00Z", edited.verifiedAt());
    }

    @Test
    void byIdIsCaseInsensitiveAndUnknownIdsCarryTheKnownOnes() {
        catalog.create("ADDR-001", "Source A", null, "https://example.eu/a",
                AddressType.URL_NEWS);
        assertEquals("ADDR-001", catalog.byId("addr-001").id());

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> catalog.byId("ADDR-999"));
        assertTrue(e.getMessage().contains("ADDR-001"));
    }

    @Test
    void removeDeletesTheAddress() {
        catalog.create("ADDR-001", "Source A", null, "https://example.eu/a",
                AddressType.URL_NEWS);
        Address removed = catalog.remove("ADDR-001");

        assertEquals("ADDR-001", removed.id());
        assertTrue(catalog.list().isEmpty());
        assertFalse(catalog.nextAddressId().orElseThrow().equals("ADDR-002"));
        assertEquals("ADDR-001", catalog.nextAddressId().orElseThrow());
    }

    @Test
    void listReturnsEveryRegisteredAddress() {
        catalog.create("ADDR-002", "Source B", null, "https://example.eu/b",
                AddressType.URL_EVENTS);
        catalog.create("ADDR-001", "Source A", null, "https://example.eu/a",
                AddressType.URL_NEWS);

        List<Address> all = catalog.list();
        assertEquals(2, all.size());
        assertEquals("ADDR-001", all.get(0).id());
    }

    @Test
    void newAddressesStartWithoutCapabilityFlags() {
        Address a = catalog.create("ADDR-001", "Source A", null,
                "https://example.eu/a", AddressType.URL_NEWS);
        assertTrue(a.capabilities().isEmpty());
    }

    @Test
    void createAndEditRejectMalformedUrls() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> catalog.create("ADDR-001", "Bad", null, "not a url",
                        AddressType.URL_NEWS));
        assertTrue(e.getMessage().contains("Invalid address URL"));

        catalog.create("ADDR-002", "Good", null, "https://example.eu/g",
                AddressType.URL_NEWS);
        IllegalArgumentException e2 = assertThrows(IllegalArgumentException.class,
                () -> catalog.update("ADDR-002", null, null, "ftp://example.eu/x", null));
        assertTrue(e2.getMessage().contains("http or https"));
        // The address is untouched by the refused edit.
        assertEquals("https://example.eu/g", catalog.byId("ADDR-002").url());
    }
}
