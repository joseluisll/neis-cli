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
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
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

    /**
     * Force-closes the response stream at the fetch deadline: a stream
     * read has no timeout of its own (see the scrape() javadoc), so this
     * is what bounds a stalled or slow-dripping server. One daemon
     * thread serves all fetches.
     */
    private final java.util.concurrent.ScheduledExecutorService watchdog =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "neis-scrape-watchdog");
                t.setDaemon(true);
                return t;
            });

    public HttpScrapeConnector(
            @Value("${neis.scrape.timeout-seconds:20}") long timeoutSeconds,
            @Value("${neis.scrape.max-bytes:10485760}") int maxBytes) {
        if (maxBytes <= 0) {
            throw new IllegalArgumentException(
                    "neis.scrape.max-bytes must be positive, got " + maxBytes);
        }
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
            // Streaming body handler: send() returns with the HEADERS,
            // the body is only materialized by readBody() — a misbehaving
            // registered address cannot buffer unbounded bytes into
            // memory (review blocking finding, 2026-11-14). The wire
            // bound is the cap; parsing a cap-sized document into the
            // jsoup DOM (and re-serializing) is inherently larger.
            HttpResponse<InputStream> response =
                    client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            int status = response.statusCode();
            if (status < 200 || status >= 300) {
                closeQuietly(response.body());
                throw failure(address, start, " returned HTTP " + status);
            }
            String contentType = response.headers().firstValue("Content-Type")
                    .orElse("unknown");
            // Content-Length pre-flight: refuse before reading a byte.
            long declared = response.headers().firstValueAsLong("Content-Length")
                    .orElse(-1);
            if (declared > maxBytes) {
                closeQuietly(response.body());
                throw failure(address, start, " Content-Length " + declared
                        + " exceeds the " + maxBytes + " byte cap"
                        + " (neis.scrape.max-bytes)");
            }
            // Once the headers arrive, HttpRequest.timeout() is spent and
            // a stream read has NO deadline on any JDK this project runs
            // (OpenJDK JDK-8258397/JDK-8208693; the request-time-scope
            // extension only shipped in JDK 26): without a watchdog, a
            // server that drips bytes or stalls after the headers could
            // wedge the scrape thread forever — the second half of the
            // misbehaving-address threat model (review blocking finding,
            // 2026-11-14). The watchdog force-closes the stream at the
            // deadline: a slow-drip transfer then hits the loop check's
            // specific reason, a hard stall surfaces as the close
            // ("closed" IOException) — either way bounded (third-round
            // review note). The deadline is total-fetch (headers
            // included), matching the bound claimed.
            long deadlineNanos = start + timeout.toNanos();
            InputStream stream = response.body();
            java.util.concurrent.ScheduledFuture<?> alarm = watchdog.schedule(
                    () -> closeQuietly(stream),
                    Math.max(0, deadlineNanos - System.nanoTime()),
                    java.util.concurrent.TimeUnit.NANOSECONDS);
            byte[] body;
            try {
                body = readBody(address, stream, start, deadlineNanos);
            } finally {
                alarm.cancel(false);
            }
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

    private void closeQuietly(java.io.Closeable c) {
        try {
            c.close();
        } catch (IOException ignored) {
            // The failure path must keep its specific reason, not this
            // close's exception (review finding, 2026-11-14).
        }
    }

    /**
     * Reads the response body under the byte cap AND the deadline,
     * aborting the transfer as soon as either is exceeded — the memory
     * bound is the cap (transient peak a small multiple of it: the
     * output buffer grows geometrically and {@code toByteArray()} copies
     * once), the time bound is the fetch timeout (review blocking
     * findings, 2026-11-14).
     */
    private byte[] readBody(Address address, InputStream body, long start,
                            long deadlineNanos) throws IOException {
        ByteArrayOutputStream out =
                new ByteArrayOutputStream(Math.min(maxBytes, 2 * 1024 * 1024));
        byte[] buffer = new byte[8192];
        long total = 0;
        try (body) {
            int n;
            while ((n = body.read(buffer)) != -1) {
                total += n;
                if (total > maxBytes) {
                    // Closing mid-transfer cancels it; nothing beyond
                    // the cap is ever buffered.
                    throw failure(address, start, " body of more than " + maxBytes
                            + " bytes exceeds the cap (neis.scrape.max-bytes)");
                }
                if (System.nanoTime() > deadlineNanos) {
                    throw failure(address, start, " fetch exceeded the "
                            + timeout.getSeconds() + "s timeout while reading the body"
                            + " (slow-drip or stalled server)");
                }
                out.write(buffer, 0, n);
            }
        }
        return out.toByteArray();
    }

    /**
     * Every failed fetch logs ONE operational line (url, reason,
     * elapsed) before failing the scrape — the promise of the class
     * javadoc; success and failure are both observable (review
     * finding, 2026-11-14).
     */
    private IllegalStateException failure(Address address, long start,
                                          String reason, Throwable cause) {
        // SLF4J logs the trailing throwable (if any) with its stack
        // trace, so the failure line keeps the full cause chain.
        log.warn("WEBSCRAPE fetch {} FAILED{} ({} ms)",
                address.url().trim(), reason, elapsedMs(start), cause);
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
