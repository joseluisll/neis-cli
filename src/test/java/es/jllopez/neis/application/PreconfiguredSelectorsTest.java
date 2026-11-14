package es.jllopez.neis.application;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The preconfigured-selector catalog (US-12.1, issue #46): a specific
 * (address + kind) entry wins over a kind-wide default; other kinds
 * never match.
 */
class PreconfiguredSelectorsTest {

    private final PreconfiguredSelectors catalog = new PreconfiguredSelectors(
            new PreconfiguredSelectorProperties(List.of(
                    new PreconfiguredSelectorProperties.Entry(
                            "ADDR-005", "event", "div.view-content > article"),
                    new PreconfiguredSelectorProperties.Entry(
                            null, "news", "ul.news-list li"))));

    @Test
    void specificEntryWinsOverTheKindDefault() {
        assertEquals(Optional.of("div.view-content > article"),
                catalog.find("ADDR-005", "event"));
    }

    @Test
    void kindDefaultServesOtherAddressesOfTheSameKind() {
        assertEquals(Optional.of("ul.news-list li"),
                catalog.find("ADDR-001", "news"));
        assertEquals(Optional.of("ul.news-list li"),
                catalog.find("ADDR-005", "news"));
    }

    @Test
    void otherKindsNeverMatch() {
        assertEquals(Optional.empty(), catalog.find("ADDR-005", "other"));
        assertEquals(Optional.empty(), catalog.find("ADDR-001", "event"));
    }

    @Test
    void blankSelectorEntriesAreIgnored() {
        PreconfiguredSelectors withBlank = new PreconfiguredSelectors(
                new PreconfiguredSelectorProperties(List.of(
                        new PreconfiguredSelectorProperties.Entry(
                                "ADDR-001", "news", "  "))));
        assertEquals(Optional.empty(), withBlank.find("ADDR-001", "news"));
    }

    @Test
    void emptyConfigurationYieldsNothing() {
        PreconfiguredSelectors empty = new PreconfiguredSelectors(
                new PreconfiguredSelectorProperties(null));
        assertEquals(Optional.empty(), empty.find("ADDR-001", "news"));
        assertTrue(empty.find(null, "news").isEmpty());
    }
}
