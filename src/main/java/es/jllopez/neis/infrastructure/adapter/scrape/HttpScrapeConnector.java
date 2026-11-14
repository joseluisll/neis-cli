package es.jllopez.neis.infrastructure.adapter.scrape;

import es.jllopez.neis.domain.model.Address;
import es.jllopez.neis.domain.model.ScrapeResult;
import es.jllopez.neis.domain.port.ContentScrapePort;
import org.jsoup.Jsoup;
import org.jsoup.helper.DataUtil;
import org.jsoup.nodes.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Connector (driven adapter) implementing the SCRAPE port with a REAL
 * network fetch (US-06.1, issue #27): java.net.http HttpClient (owner
 * technology choice, 2026-11-11) downloads the page, and jsoup parses
 * and normalizes it to HTML5. Redirects are followed; any 2xx response
 * is accepted and non-2xx fails the scrape so the chain records it.
 *
 * <p>Charset handling is jsoup's detection for now (BOM, meta
 * declaration, default UTF-8); the Apache Tika content-type/charset
 * detection of US-06.2 (issue #28) refines this. Content-type and raw
 * size are taken from the HTTP response.</p>
 *
 * <p>One operational log line per fetch (url, status, content type,
 * size, elapsed) — never a protocol dump.</p>
 *
 * <p>Selected per address through {@link ScrapeConnectorRouter}; the
 * static fixture connector remains the default for offline runs. The
 * paginated (page index) and filtered variants are not supported yet:
 * the port defaults throw, and the chain reports that instead of
 * failing the run.</p>
 */
@Component
public class HttpScrapeConnector implements ContentScrapePort {

    private static final Logger log = LoggerFactory.getLogger(HttpScrapeConnector.class);

    private final HttpClient client;
    private final Duration timeout;
    private final int maxBytes;

    public HttpScrapeConnector(
            @Value("${neis.scrape.timeout-seconds:20}") long timeoutSeconds,
            @Value("${neis.scrape.max-bytes:10485760}") int maxBytes) {
        this.timeout = Duration.ofSeconds(timeoutSeconds);
        this.maxBytes = maxBytes;
        this.client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(timeout)
                .build();
    }

    @Override
    public ScrapeResult scrape(Address address) {
        String url = address.url().trim();
        long start = System.nanoTime();
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(timeout)
                    .header("User-Agent", "NEIS-web-scrape/0.1")
                    .header("Accept", "text/html, application/xhtml+xml, */*")
                    .GET()
                    .build();
            HttpResponse<byte[]> response =
                    client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            int status = response.statusCode();
            if (status < 200 || status >= 300) {
                throw failure(address, start, " returned HTTP " + status);
            }
            byte[] body = response.body();
            if (body.length > maxBytes) {
                throw failure(address, start, " body of " + body.length
                        + " bytes exceeds the " + maxBytes + " byte cap"
                        + " (neis.scrape.max-bytes)");
            }
            String contentType = response.headers().firstValue("Content-Type")
                    .orElse("unknown");
            // jsoup detects the charset (BOM / meta declaration / UTF-8
            // default) and normalizes the markup to HTML5. The response
            // header's charset is NOT honored yet — Tika content-type/
            // charset detection lands with US-06.2 (issue #28).
            Document doc = DataUtil.load(new ByteArrayInputStream(body), null, url);
            String html = doc.html();
            // sizeBytes follows the static connector's convention: the
            // byte size of the content actually stored (the normalized
            // HTML5), not the raw wire size (review finding, 2026-11-14).
            int storedBytes = html.getBytes(StandardCharsets.UTF_8).length;
            log.info("WEBSCRAPE fetch {} -> HTTP {} {} wire {} bytes, stored {} bytes ({} ms)",
                    url, status, contentType, body.length, storedBytes, elapsedMs(start));
            return new ScrapeResult(html, contentType, storedBytes);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw failure(address, start, " interrupted", e);
        } catch (IOException e) {
            throw failure(address, start, " failed: " + e, e);
        }
    }

    /**
     * Every failed fetch logs ONE operational line (url, reason,
     * elapsed) before failing the scrape — the promise of the class
     * javadoc; success and failure are both observable (review
     * finding, 2026-11-14).
     */
    private IllegalStateException failure(Address address, long start,
                                          String reason, Throwable cause) {
        log.warn("WEBSCRAPE fetch {} FAILED{} ({} ms)",
                address.url().trim(), reason, elapsedMs(start));
        return new IllegalStateException("WEBSCRAPE fetch of "
                + address.url().trim() + reason, cause);
    }

    private IllegalStateException failure(Address address, long start, String reason) {
        return failure(address, start, reason, null);
    }

    private long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
