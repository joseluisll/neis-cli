package es.jllopez.neis.infrastructure.adapter.scrape;

import com.sun.net.httpserver.HttpServer;
import es.jllopez.neis.domain.model.Address;
import es.jllopez.neis.domain.model.AddressType;
import es.jllopez.neis.domain.model.ScrapeResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The live fetch connector (US-06.1, issue #27) against a local HTTP
 * server — real HTTP traffic, no external network: a 2xx response is
 * fetched, jsoup-normalized to HTML5 and returned with the response's
 * content type and raw size; error statuses and unreachable servers
 * fail the scrape.
 */
class HttpScrapeConnectorTest {

    private HttpServer server;
    private int port;

    private final HttpScrapeConnector connector = new HttpScrapeConnector(5, 1024 * 1024);

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        // Daemon executor: a handler that sleeps past server.stop(0)
        // (the stall test) must not keep the surefire fork alive
        // (third-round review note).
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r);
            t.setDaemon(true);
            return t;
        }));
        server.start();
        port = server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private Address addressAt(String path) {
        return new Address("ADDR-T", "Test page", null,
                "http://127.0.0.1:" + port + path, AddressType.URL_NEWS);
    }

    @Test
    void fetchesAndNormalizesHtml() {
        String raw = "<!DOCTYPE html><HTML><HEAD><TITLE>listing</TITLE></HEAD>"
                + "<BODY><BR><P>unclosed paragraph"
                + "</BODY></HTML>";
        server.createContext("/page", exchange -> {
            byte[] body = raw.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/html; charset=UTF-8");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });

        ScrapeResult result = connector.scrape(addressAt("/page"));

        assertEquals("text/html; charset=UTF-8", result.contentType());
        assertTrue(result.sizeBytes() > 0);
        // jsoup normalized the markup to HTML5: lowercase tags, closed
        // elements, the doctype preserved.
        assertTrue(result.html().contains("<title>listing</title>"),
                "title normalized to lowercase: " + result.html());
        assertTrue(result.html().contains("<br>"),
                "br normalized to HTML5 void element: " + result.html());
        assertTrue(result.html().contains("<p>unclosed paragraph</p>"),
                "paragraph closed: " + result.html());
        assertTrue(result.html().contains("<!doctype html>"),
                "doctype preserved: " + result.html());
    }

    @Test
    void detectsCharsetFromMetaDeclaration() {
        // Body encoded in ISO-8859-1 WITHOUT a Content-Type charset: the
        // meta declaration is the only signal (jsoup detection, Tika
        // refines in US-06.2).
        String raw = "<!DOCTYPE html><html><head>"
                + "<meta http-equiv=\"Content-Type\" content=\"text/html; charset=ISO-8859-1\">"
                + "<title>caf\u00e9</title></head><body>caf\u00e9 menu</body></html>";
        byte[] body = raw.getBytes(StandardCharsets.ISO_8859_1);
        server.createContext("/meta", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "text/html");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });

        ScrapeResult result = connector.scrape(addressAt("/meta"));

        assertTrue(result.html().contains("café menu"),
                "ISO-8859-1 body decoded via meta declaration: " + result.html());
    }

    @Test
    void errorStatusFailsTheScrape() {
        server.createContext("/missing", exchange -> {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> connector.scrape(addressAt("/missing")));
        assertTrue(e.getMessage().contains("404"));
    }

    @Test
    void oversizedBodyFailsTheScrape() {
        byte[] body = new byte[4096]; // larger than the 1024-byte cap below
        server.createContext("/huge", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "text/html");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        HttpScrapeConnector capped = new HttpScrapeConnector(5, 1024);

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> capped.scrape(addressAt("/huge")));
        assertTrue(e.getMessage().contains("byte cap"), e.getMessage());
    }

    @Test
    void sizeBytesIsTheStoredNormalizedSize() {
        // The convention (shared with the static connector) is the byte
        // size of the content actually stored, the jsoup-normalized
        // HTML5 — not the raw wire size (review finding, 2026-11-14).
        server.createContext("/size", exchange -> {
            byte[] body = "<html><body>hello</body></html>".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/html");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });

        ScrapeResult result = connector.scrape(addressAt("/size"));

        assertEquals(result.html().getBytes(StandardCharsets.UTF_8).length,
                result.sizeBytes());
    }

    @Test
    void headerCharsetIsHonored() {
        // US-06.2: the response header's charset parameter wins — an
        // ISO-8859-1 body WITHOUT a meta declaration is decoded
        // correctly (this behavior was pinned as 'not honored yet'
        // before the Tika slice landed).
        byte[] body = "<html><body>caf\351 menu</body></html>"
                .getBytes(StandardCharsets.ISO_8859_1);
        server.createContext("/header-charset", exchange -> {
            exchange.getResponseHeaders().add("Content-Type",
                    "text/html; charset=ISO-8859-1");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });

        ScrapeResult result = connector.scrape(addressAt("/header-charset"));

        assertTrue(result.contentType().contains("ISO-8859-1"),
                "response content type is recorded verbatim");
        assertTrue(result.html().contains("café menu"),
                "ISO-8859-1 body decoded via the header charset: " + result.html());
    }

    @Test
    void bomCharsetIsDetectedByTika() {
        // No charset in the header and no meta declaration: the UTF-16LE
        // BOM is the only signal — Tika's encoding chain resolves it
        // (US-06.2).
        byte[] body = "\ufeff<html><body>caf\u00e9 menu</body></html>"
                .getBytes(StandardCharsets.UTF_16LE);
        server.createContext("/bom", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "text/html");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });

        ScrapeResult result = connector.scrape(addressAt("/bom"));

        assertTrue(result.html().contains("café menu"),
                "UTF-16LE body decoded via the BOM: " + result.html());
    }

    @Test
    void nonTextContentIsRejected() {
        // A listing is a text page: a PDF (declared AND magic-matched)
        // fails the scrape with an explicit reason (US-06.2).
        byte[] body = new byte[]{'%', 'P', 'D', 'F', '-', '1', '.', '4'};
        server.createContext("/pdf", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "application/pdf");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> connector.scrape(addressAt("/pdf")));
        assertTrue(e.getMessage().contains("application/pdf"), e.getMessage());
        assertTrue(e.getMessage().contains("text/HTML"), e.getMessage());
    }

    @Test
    void unreachableServerFailsTheScrape() throws IOException {
        // Bind an ephemeral port, CLOSE it, then target it: nothing
        // listens there, so the fetch hits a real connection-refused
        // (binding and scraping inside the try-with-resources would
        // leave the socket accepting and turn the test into a silent
        // black hole — review finding, 2026-11-14).
        int port;
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        Address dead = new Address("ADDR-DEAD", "Dead", null,
                "http://127.0.0.1:" + port + "/page", AddressType.URL_NEWS);
        assertThrows(IllegalStateException.class, () -> connector.scrape(dead));
    }

    @Test
    void oversizedChunkedBodyFailsDuringStreaming() {
        // No Content-Length (chunked transfer): the cap must abort
        // MID-STREAM, not after the whole body is buffered (review
        // blocking finding, 2026-11-14).
        byte[] chunk = new byte[4096];
        server.createContext("/chunked", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "text/html");
            exchange.sendResponseHeaders(200, 0); // chunked: no length
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(chunk);
                out.write(chunk);
            }
        });
        HttpScrapeConnector capped = new HttpScrapeConnector(5, 1024);

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> capped.scrape(addressAt("/chunked")));
        assertTrue(e.getMessage().contains("exceeds the cap"), e.getMessage());
    }

    @Test
    void nonPositiveMaxBytesIsRejectedAtStartup() {
        assertThrows(IllegalArgumentException.class,
                () -> new HttpScrapeConnector(5, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new HttpScrapeConnector(5, -1));
    }

    @Test
    void stalledBodyFailsAtTheDeadline() {
        // A request timeout does NOT cover a streamed body read (it is
        // spent once the headers arrive; JDK-8258397): without the
        // watchdog a server that sends headers and then stalls would
        // wedge the scrape thread forever (review blocking finding,
        // 2026-11-14). With a 1s fetch timeout the scrape must fail in
        // about a second, not hang.
        server.createContext("/stall", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "text/html");
            exchange.sendResponseHeaders(200, 8192); // headers, then nothing
            try {
                Thread.sleep(10_000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        });
        HttpScrapeConnector impatient = new HttpScrapeConnector(1, 1024 * 1024);

        long t0 = System.nanoTime();
        assertThrows(IllegalStateException.class,
                () -> impatient.scrape(addressAt("/stall")));
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;
        assertTrue(elapsedMs < 5_000,
                "stalled fetch returned in " + elapsedMs + " ms, not bounded");
    }
}
