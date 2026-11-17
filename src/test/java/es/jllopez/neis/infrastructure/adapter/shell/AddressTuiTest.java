package es.jllopez.neis.infrastructure.adapter.shell;

import es.jllopez.neis.application.AddressCatalogService;
import es.jllopez.neis.domain.model.Address;
import es.jllopez.neis.domain.model.AddressType;
import es.jllopez.neis.domain.service.UrlSyntaxValidator;
import es.jllopez.neis.testsupport.InMemoryAddressCatalogPort;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The address-management TUI (US-09.1, issue #37): a scripted console
 * drives the screen loop — list, add (auto id), edit (empty keeps),
 * remove (explicit confirm), loud refusals that leave the catalog
 * untouched, clean EOF exit.
 */
class AddressTuiTest {

    private final InMemoryAddressCatalogPort port = new InMemoryAddressCatalogPort(
            List.of(new Address("ADDR-001", "News",
                    null, "https://commission.europa.eu/news-and-media/news_en",
                    AddressType.URL_NEWS)));
    private final AddressCatalogService catalog = new AddressCatalogService(
            port, new UrlSyntaxValidator());

    /** Scripted console: queued inputs in, captured lines out. */
    private static final class ScriptedConsole implements ConsoleIO {
        private final Deque<String> inputs = new ArrayDeque<>();
        private final List<String> output = new ArrayList<>();

        ScriptedConsole(String... inputs) {
            for (String input : inputs) {
                this.inputs.add(input);
            }
        }

        @Override
        public String readLine(String prompt) {
            return inputs.poll();
        }

        @Override
        public void println(String line) {
            output.add(line);
        }

        String transcript() {
            return String.join("\n", output);
        }

        boolean fedAllInputs() {
            return inputs.isEmpty();
        }
    }

    private void seedVerified() {
        // A VERIFIED address exercises the status column rendering.
        catalog.updateVerification("ADDR-001",
                es.jllopez.neis.domain.model.VerificationStatus.VERIFIED,
                "2026-11-15T10:00:00Z", null);
    }

    @Test
    void listShowsAddressesWithTheirStatusThenQuits() {
        seedVerified();
        ScriptedConsole io = new ScriptedConsole("l", "q");

        new AddressTui(catalog, io).run();

        String transcript = io.transcript();
        assertTrue(transcript.contains("ADDR-001"), transcript);
        assertTrue(transcript.contains("validation: VALID"), transcript);
        assertTrue(transcript.contains("verification: VERIFIED"), transcript);
        assertTrue(transcript.contains("(2026-11-15T10:00:00Z)"), transcript);
        assertTrue(transcript.contains("unclassified"), transcript);
        assertTrue(transcript.contains("Bye."), transcript);
        assertTrue(io.fedAllInputs());
    }

    @Test
    void listShowsTheSyntaxValidationOutcomeOfTheStoredUrl() {
        // US-09.2, issue #38: the validation/verification status of
        // EACH address — here an INVALID stored url with the
        // validator's reason, never a live probe. The hand-seeded
        // port bypasses the catalog's create/update gate, which is
        // exactly the state a status view must surface loud.
        port.save(new Address("ADDR-BAD", "Broken", null,
                "not-a-url", AddressType.URL_NEWS));
        ScriptedConsole io = new ScriptedConsole("l", "q");

        new AddressTui(catalog, io).run();

        String transcript = io.transcript();
        assertTrue(transcript.contains("validation: INVALID ("
                + "the URL has no scheme (expected http or https): not-a-url)"),
                transcript);
        assertTrue(transcript.contains("verification: UNVERIFIED"), transcript);
        // The valid row stays VALID on the same screen.
        assertTrue(transcript.contains("validation: VALID"), transcript);
    }

    @Test
    void addCreatesAnAddressWithTheNextFreeId() {
        ScriptedConsole io = new ScriptedConsole(
                "a",
                "",                              // id: empty -> next free
                "Events",
                "Commission events listing",
                "https://commission.europa.eu/events_en",
                "EVENTS",
                "q");

        new AddressTui(catalog, io).run();

        Address created = catalog.byId("ADDR-002");
        assertEquals("https://commission.europa.eu/events_en", created.url());
        assertEquals(AddressType.URL_EVENTS, created.type());
        assertEquals("Events", created.name());
        assertEquals("Commission events listing", created.description());
        assertTrue(io.transcript().contains("Registered ADDR-002"),
                io.transcript());
        assertEquals("UNVERIFIED", created.verificationStatus().name());
    }

    @Test
    void anInvalidListingKindAbortsTheAddWithoutCreatingAnything() {
        int before = catalog.list().size();
        ScriptedConsole io = new ScriptedConsole(
                "a", "", "X", "desc", "https://example.eu/x", "NEWSLETTER", "q");

        new AddressTui(catalog, io).run();

        assertEquals(before, catalog.list().size(), "nothing created");
        assertTrue(io.transcript().contains("Refused"), io.transcript());
    }

    @Test
    void anInvalidUrlAbortsTheAddWithoutCreatingAnything() {
        int before = catalog.list().size();
        ScriptedConsole io = new ScriptedConsole(
                "a", "", "Bad", "desc", "not-a-url", "NEWS", "q");

        new AddressTui(catalog, io).run();

        assertEquals(before, catalog.list().size(), "nothing created");
        assertTrue(io.transcript().contains("Refused"), io.transcript());
    }

    @Test
    void addRefusesAnAlreadyRegisteredIdInsteadOfOverwritingIt() {
        // US-09.1 review finding: the TUI used to silently re-register
        // (overwrite) an existing id — editing is the explicit path.
        int before = catalog.list().size();
        ScriptedConsole io = new ScriptedConsole(
                "a", "ADDR-001", "Dup", "desc",
                "https://example.eu/dup", "NEWS", "q");

        new AddressTui(catalog, io).run();

        assertEquals(before, catalog.list().size(), "nothing created");
        assertTrue(io.transcript().contains("already exists"), io.transcript());
        assertEquals("https://commission.europa.eu/news-and-media/news_en",
                catalog.byId("ADDR-001").url(), "the record was not touched");
    }

    @Test
    void removeReportsAnUnknownIdWithoutAskingForConfirmation() {
        // US-09.1 review finding: the confirmation was asked BEFORE the
        // id was known to exist — the user confirmed a removal that
        // could not happen.
        ScriptedConsole io = new ScriptedConsole("r", "ADDR-999", "q");
        new AddressTui(catalog, io).run();
        assertTrue(io.transcript().contains("Unknown address id"), io.transcript());
        assertNotNull(catalog.byId("ADDR-001"), "nothing was removed");
    }

    @Test
    void editKeepsEveryFieldLeftEmptyAndTheVerificationState() {
        seedVerified();
        ScriptedConsole io = new ScriptedConsole(
                "e",
                "ADDR-001",
                "",    // name kept
                "",    // description kept
                "https://commission.europa.eu/news-and-media/news-policy_en",
                "",    // kind kept
                "q");

        new AddressTui(catalog, io).run();

        Address edited = catalog.byId("ADDR-001");
        assertEquals("News", edited.name(), "empty input keeps the name");
        assertNull(edited.description());
        assertEquals("https://commission.europa.eu/news-and-media/news-policy_en",
                edited.url());
        assertEquals(es.jllopez.neis.domain.model.VerificationStatus.VERIFIED,
                edited.verificationStatus(), "catalog edits never touch verification");
        assertTrue(io.transcript().contains("verification state kept: VERIFIED"),
                io.transcript());
    }

    @Test
    void removeNeedsAnExplicitConfirmation() {
        ScriptedConsole cancel = new ScriptedConsole(
                "r", "ADDR-001", "n", "q");
        new AddressTui(catalog, cancel).run();
        assertNotNull(catalog.byId("ADDR-001"), "n cancels the removal");

        ScriptedConsole confirm = new ScriptedConsole(
                "r", "ADDR-001", "y", "q");
        new AddressTui(catalog, confirm).run();
        IllegalArgumentException e = assertThrowsById("ADDR-001");
        assertTrue(e.getMessage().contains("Unknown address"), e.getMessage());
    }

    @Test
    void unknownCommandIsNotedAndTheLoopContinues() {
        ScriptedConsole io = new ScriptedConsole("make-me-rich", "q");
        new AddressTui(catalog, io).run();
        assertTrue(io.transcript().contains("Unknown command 'make-me-rich'"),
                io.transcript());
        assertFalse(io.transcript().contains("Refused"));
    }

    @Test
    void inputEndExitsCleanlyWithoutSpinning() {
        ScriptedConsole io = new ScriptedConsole();
        new AddressTui(catalog, io).run();
        assertTrue(io.transcript().contains("(input ended)"), io.transcript());
    }

    private IllegalArgumentException assertThrowsById(String id) {
        try {
            catalog.byId(id);
        } catch (IllegalArgumentException e) {
            return e;
        }
        throw new AssertionError("Expected the address " + id + " to be gone");
    }
}
