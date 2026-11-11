package es.jllopez.neis.infrastructure.adapter.verification;

import com.sun.net.httpserver.HttpServer;
import es.jllopez.neis.domain.model.Address;
import es.jllopez.neis.domain.model.AddressType;
import es.jllopez.neis.domain.model.VerificationOutcome;
import es.jllopez.neis.domain.model.VerificationStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The verification connector (US-04.2, issue #22) against a local HTTP
 * server — real HTTP traffic, no external network: 2xx verifies, error
 * responses and unreachable servers mark FAILED, HEAD rejections fall
 * back to GET.
 */
class HttpClientAddressVerificationConnectorTest {

    private HttpServer server;
    private int port;
    private final AtomicInteger headHits = new AtomicInteger();
    private final AtomicInteger getHits = new AtomicInteger();

    private final HttpClientAddressVerificationConnector connector =
            new HttpClientAddressVerificationConnector(5);

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ok", exchange -> {
            if ("HEAD".equals(exchange.getRequestMethod())) {
                headHits.incrementAndGet();
                exchange.sendResponseHeaders(200, -1);
            } else {
                getHits.incrementAndGet();
                byte[] body = "hello".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "text/html");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
            exchange.close();
        });
        server.createContext("/missing", exchange -> {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        server.createContext("/headless", exchange -> {
            if ("HEAD".equals(exchange.getRequestMethod())) {
                headHits.incrementAndGet();
                exchange.sendResponseHeaders(405, -1); // HEAD refused
            } else {
                getHits.incrementAndGet();
                exchange.sendResponseHeaders(200, -1);
            }
            exchange.close();
        });
        server.start();
        port = server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private Address address(String path) {
        return new Address("ADDR-V", "Verification fixture",
                "http://127.0.0.1:" + port + path, AddressType.URL_NEWS);
    }

    @Test
    void a2xxResponseVerifiesTheAddress() {
        VerificationOutcome outcome = connector.verify(address("/ok"));
        assertEquals(VerificationStatus.VERIFIED, outcome.status());
        assertEquals(200, outcome.statusCode());
        assertTrue(outcome.checkedAt() != null && !outcome.checkedAt().isBlank());
    }

    @Test
    void anErrorResponseMarksTheAddressFailed() {
        VerificationOutcome outcome = connector.verify(address("/missing"));
        assertEquals(VerificationStatus.FAILED, outcome.status());
        assertEquals(404, outcome.statusCode());
    }

    @Test
    void aHeadRejectionFallsBackToGet() {
        VerificationOutcome outcome = connector.verify(address("/headless"));
        assertEquals(VerificationStatus.VERIFIED, outcome.status());
        assertEquals(1, headHits.get());
        assertEquals(1, getHits.get(), "the GET fallback must run after a 405");
    }

    @Test
    void anUnreachableServerMarksTheAddressFailedWithoutThrowing() {
        // Port closed: bind a socket, capture the port, release it.
        int closedPort;
        try (var socket = new java.net.ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        Address unreachable = new Address("ADDR-V2", "Unreachable",
                "http://127.0.0.1:" + closedPort + "/ok", AddressType.URL_NEWS);

        VerificationOutcome outcome = connector.verify(unreachable);
        assertEquals(VerificationStatus.FAILED, outcome.status());
        assertEquals(0, outcome.statusCode(), "no HTTP status was ever received");
        assertTrue(outcome.detail() != null && !outcome.detail().isBlank());
    }
}
