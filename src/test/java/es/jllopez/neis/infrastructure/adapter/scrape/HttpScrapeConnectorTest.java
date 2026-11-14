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

    private final HttpScrapeConnector connector = new HttpScrapeConnector(5);

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
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
    void unreachableServerFailsTheScrape() {
        // Port 1 on localhost: nothing listens there.
        Address dead = new Address("ADDR-DEAD", "Dead", null,
                "http://127.0.0.1:1/page", AddressType.URL_NEWS);
        assertThrows(IllegalStateException.class, () -> connector.scrape(dead));
    }
}
