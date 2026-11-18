package es.jllopez.neis.application;

import es.jllopez.neis.domain.model.Address;
import es.jllopez.neis.domain.model.AddressType;
import es.jllopez.neis.domain.model.Capture;
import es.jllopez.neis.domain.model.WebpageType;
import es.jllopez.neis.domain.port.AddressPreflightPort;
import es.jllopez.neis.domain.port.AddressCatalogPort;
import es.jllopez.neis.domain.service.UrlSyntaxValidator;
import es.jllopez.neis.testsupport.InMemoryAddressCatalogPort;
import es.jllopez.neis.testsupport.InMemoryContentStorePort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The address preflight use case (US-13.6, issue #65): the probe's
 * classification is persisted onto the address (the executor consults
 * it before scheduling WEBSCRAPE runs) and the probe record onto the
 * capture metadata (V18 {@code captures.preflight}) — under the
 * never-wipes rule; a probe without a classification never wipes a
 * stored type or record.
 */
class AddressPreflightServiceTest {

    @TempDir
    Path workDir;

    private final Address address = new Address("ADDR-001", "News",
            "https://example.eu/news", AddressType.URL_NEWS);

    /** A probe answering with the given outcome. */
    private AddressPreflightPort probe(String webpageType, String connector) {
        return a -> new AddressPreflightPort.Outcome(webpageType, connector,
                "{\"webpageType\":" + (webpageType == null ? "null"
                        : "\"" + webpageType + "\"") + "}",
                "evidence: " + webpageType);
    }

    @Test
    void theClassificationIsPersistedOntoTheAddressAndTheProbeOntoTheCapture() {
        InMemoryAddressCatalogPort port =
                new InMemoryAddressCatalogPort(List.of(address));
        AddressCatalogService catalog = new AddressCatalogService(
                port, new UrlSyntaxValidator());
        InMemoryContentStorePort store = new InMemoryContentStorePort();
        AddressPreflightService service = new AddressPreflightService(catalog,
                new ContentStoreService(store, workDir.toString()),
                probe("static", "http"));

        var outcome = service.preflight("ADDR-001");

        assertEquals("static", outcome.webpageType());
        // The executor's routing metadata: the address now carries the type.
        assertEquals("static", catalog.byId("ADDR-001").webpageType());
        // The probe evidence: recorded with the capture metadata.
        Capture capture = store.findCapture("ADDR-001",
                new ContentStoreService(store, workDir.toString())
                        .captureId(address.url())).orElseThrow();
        assertNotNull(capture.preflight());
        assertTrue(capture.preflight().contains("\"webpageType\":\"static\""));
    }

    @Test
    void aProbeWithoutAClassificationNeverWipesTheStoredType() {
        InMemoryAddressCatalogPort port =
                new InMemoryAddressCatalogPort(List.of(
                        address.withWebpageType("spa-angular")));
        AddressCatalogService catalog = new AddressCatalogService(
                port, new UrlSyntaxValidator());
        InMemoryContentStorePort store = new InMemoryContentStorePort();
        AddressPreflightService service = new AddressPreflightService(catalog,
                new ContentStoreService(store, workDir.toString()),
                probe(null, null));

        service.preflight("ADDR-001");

        assertEquals("spa-angular", catalog.byId("ADDR-001").webpageType(),
                "a failed classification must not wipe a stored type");
    }

    @Test
    void aRePreflightNeverWipesTheStoredProbeRecord() {
        InMemoryAddressCatalogPort port =
                new InMemoryAddressCatalogPort(List.of(address));
        AddressCatalogService catalog = new AddressCatalogService(
                port, new UrlSyntaxValidator());
        ContentStoreService contentStore =
                new ContentStoreService(new InMemoryContentStorePort(), workDir.toString());
        AddressPreflightService first = new AddressPreflightService(catalog,
                contentStore, probe("static", "http"));
        first.preflight("ADDR-001");

        // A second probe records nothing (null JSON): the stored record stays.
        AddressPreflightService second = new AddressPreflightService(catalog,
                contentStore, new AddressPreflightPort() {
            @Override
            public Outcome preflight(Address a) {
                return new Outcome("static", "http", null, "evidence");
            }
        });
        second.preflight("ADDR-001");

        Capture capture = contentStore.store()
                .findCapture("ADDR-001", contentStore.captureId(address.url()))
                .orElseThrow();
        assertNotNull(capture.preflight(), "the stored probe must survive");
        assertTrue(capture.preflight().contains("\"webpageType\":\"static\""));
    }
}
