package es.jllopez.neis.application;

import es.jllopez.neis.domain.model.Address;
import es.jllopez.neis.domain.model.AddressType;
import es.jllopez.neis.domain.service.UrlSyntaxValidator;
import es.jllopez.neis.testsupport.InMemoryAddressCatalogPort;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The referential check of the preconfigured WEBSCRAPE filters
 * (US-06.10 review finding): a configured entry naming an address the
 * catalog does not know fails the boot loud, naming every dangling id
 * — a syntax-valid but dangling id used to boot fine and stay dead
 * forever, never applying and never surfacing.
 */
class PreconfiguredFilterAddressCheckTest {

    private AddressCatalogService catalogWithIds(String... ids) {
        return new AddressCatalogService(
                new InMemoryAddressCatalogPort(java.util.Arrays.stream(ids)
                        .map(id -> new Address(id, "Address " + id, null,
                                "https://example.eu/" + id, AddressType.URL_NEWS))
                        .toList()),
                new UrlSyntaxValidator());
    }

    private static PreconfiguredFilterProperties properties(
            String address, String name, String value) {
        return new PreconfiguredFilterProperties(List.of(
                new PreconfiguredFilterProperties.Entry(address, name, value)));
    }

    @Test
    void aDanglingAddressIdFailsTheBootNamingTheEntry() {
        PreconfiguredFilterAddressCheck check = new PreconfiguredFilterAddressCheck(
                properties("ADDR-099", "programme", "EU4Health"),
                catalogWithIds("ADDR-001", "ADDR-006"));

        IllegalStateException e = assertThrows(IllegalStateException.class,
                check::checkConfiguredAddressesExist);
        assertTrue(e.getMessage().contains("ADDR-099"), e.getMessage());
        assertTrue(e.getMessage().contains("programme"), e.getMessage());
        assertTrue(e.getMessage().contains("address add"), e.getMessage());
    }

    @Test
    void everyDanglingIdIsReportedInOneFailure() {
        PreconfiguredFilterProperties properties = new PreconfiguredFilterProperties(List.of(
                new PreconfiguredFilterProperties.Entry("ADDR-098", "a", "x"),
                new PreconfiguredFilterProperties.Entry("ADDR-001", "ok", "y"),
                new PreconfiguredFilterProperties.Entry("ADDR-099", "b", "z")));
        PreconfiguredFilterAddressCheck check = new PreconfiguredFilterAddressCheck(
                properties, catalogWithIds("ADDR-001"));

        IllegalStateException e = assertThrows(IllegalStateException.class,
                check::checkConfiguredAddressesExist);
        assertTrue(e.getMessage().contains("2 configured filter(s)"), e.getMessage());
        assertTrue(e.getMessage().contains("ADDR-098") && e.getMessage().contains("ADDR-099"),
                e.getMessage());
        assertFalse(e.getMessage().contains("'ok'"),
                "the resolvable entry is not implicated: " + e.getMessage());
    }

    @Test
    void resolvableIdsPassAndTheEmptyConfigIsANoOp() {
        new PreconfiguredFilterAddressCheck(
                properties("ADDR-006", "programme", "EU4Health"),
                catalogWithIds("ADDR-006"))
                .checkConfiguredAddressesExist();

        // An empty configuration never touches the catalog.
        new PreconfiguredFilterAddressCheck(
                new PreconfiguredFilterProperties(List.of()),
                catalogWithIds() /* empty catalog */)
                .checkConfiguredAddressesExist();
    }

    @Test
    void aCaseVariantDuplicateAddressNamePairIsRefusedAtBindTime() {
        // US-06.10 re-review finding: the duplicate check keyed the
        // address raw — 'ADDR-006 programme' + 'addr-006 PROGRAMME'
        // passed the bind check and silently overwrote its twin at run
        // time (find() matches case-insensitively). Refused now.
        assertThrows(IllegalArgumentException.class,
                () -> new PreconfiguredFilterProperties(List.of(
                        new PreconfiguredFilterProperties.Entry("ADDR-006",
                                "programme", "EU4Health"),
                        new PreconfiguredFilterProperties.Entry("addr-006",
                                "PROGRAMME", "Horizon"))));
        // Different names for the same (case-variant) address still merge.
        PreconfiguredFilters merging = new PreconfiguredFilters(
                new PreconfiguredFilterProperties(List.of(
                        new PreconfiguredFilterProperties.Entry("ADDR-006",
                                "programme", "EU4Health"),
                        new PreconfiguredFilterProperties.Entry("addr-006",
                                "lang", "en"))));
        var merged = merging.find("ADDR-006").orElseThrow();
        assertEquals(2, merged.size());
        assertEquals("EU4Health", merged.get("programme"));
        assertEquals("en", merged.get("lang"),
                "both entries served for one address, config order");
    }

    @Test
    void theIdMatchIsCaseInsensitiveThroughToTheRunTimeMatcher() {
        // US-06.10 review blocking finding: the check resolved ids
        // case-insensitively (the catalog semantics) while find() used
        // exact equals — a case-variant id passed the boot yet never
        // applied. Both ends are aligned now; the boot check and the
        // runtime matcher must agree on the SAME case-variant entry.
        PreconfiguredFilters filters = new PreconfiguredFilters(
                properties("addr-006", "programme", "EU4Health"));
        assertDoesNotThrow(() -> new PreconfiguredFilterAddressCheck(
                properties("addr-006", "programme", "EU4Health"),
                catalogWithIds("ADDR-006")).checkConfiguredAddressesExist());
        assertTrue(filters.find("ADDR-006").isPresent(),
                "the runtime matcher serves the case-variant configured id");
    }
}
