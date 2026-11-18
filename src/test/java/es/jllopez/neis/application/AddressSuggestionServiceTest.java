package es.jllopez.neis.application;

import org.junit.jupiter.api.Test;

import es.jllopez.neis.domain.model.AddressType;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The AI-assisted address registration (US-13.5, issue #64): the
 * proposal parser of the suggestion contract. The suggestion is a
 * PROPOSAL — the parser never touches the catalog; the shell command
 * renders it and the user registers (possibly edited values) through
 * the ordinary 'address add' path.
 */
class AddressSuggestionServiceTest {

    @Test
    void aFullProposalIsParsed() {
        var s = AddressSuggestionService.parseSuggestion("https://example.eu/news", """
                {"name":"Commission news","type":"news",
                 "description":"Latest news of the Commission",
                 "metadata":{"publisher":"European Commission","language":"en"}}
                """);
        assertEquals("Commission news", s.name());
        assertEquals(AddressType.URL_NEWS, s.type());
        assertEquals("Latest news of the Commission", s.description());
        assertEquals(2, s.metadata().size());
        assertEquals("European Commission", s.metadata().get("publisher"));
        assertEquals("https://example.eu/news", s.url());
    }

    @Test
    void anEventsProposalMapsToUrlEvents() {
        var s = AddressSuggestionService.parseSuggestion("https://example.eu/events",
                "{\"name\":\"Events\",\"type\":\"event\",\"description\":\"d\",\"metadata\":{}}");
        assertEquals(AddressType.URL_EVENTS, s.type());
        assertTrue(s.metadata().isEmpty());
    }

    @Test
    void anOtherTypeYieldsNullForTheUserToDecide() {
        var s = AddressSuggestionService.parseSuggestion("https://example.eu/x",
                "{\"name\":\"X\",\"type\":\"other\",\"description\":null,\"metadata\":{}}");
        assertNull(s.type(), "'other' is not a registered listing kind");
        assertNull(s.description());
    }

    @Test
    void aMalformedAnswerYieldsAProposalWithoutGuesses() {
        var s = AddressSuggestionService.parseSuggestion("https://example.eu/x",
                "I could not analyze the page, sorry.");
        assertNull(s.type());
        assertNull(s.name());
        assertTrue(s.metadata().isEmpty());
    }

    @Test
    void metadataValuesSurviveMultiplePairs() {
        var s = AddressSuggestionService.parseSuggestion("https://example.eu/x",
                "{\"name\":\"n\",\"type\":\"news\",\"description\":\"d\","
                        + "\"metadata\":{\"feed\":\"https://example.eu/rss\",\"lang\":\"en\"}}");
        assertEquals("https://example.eu/rss", s.metadata().get("feed"));
        assertEquals("en", s.metadata().get("lang"));
    }
}
