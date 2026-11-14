package es.jllopez.neis.infrastructure.adapter.scrape;

import es.jllopez.neis.domain.model.Address;
import es.jllopez.neis.domain.model.ScrapeResult;
import es.jllopez.neis.domain.port.ContentScrapePort;
import org.apache.tika.detect.DefaultEncodingDetector;
import org.apache.tika.detect.EncodingDetector;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.mime.MediaType;
import org.apache.tika.mime.MimeTypes;
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
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Connector (driven adapter) implementing the SCRAPE port with a REAL
 * network fetch (US-06.1/#27, US-06.2/#28): java.net.http HttpClient
 * (owner technology choice, 2026-11-11) downloads the page, Apache
 * Tika (tika-core) handles the content-type and charset handling, and
 * jsoup parses and normalizes it to HTML5.
 *
 * <p>Content types (US-06.2): the response's Content-Type decides; a
 * missing or generic one (application/octet-stream) is re-detected by
 * Tika's MIME registry from the bytes. Only text pages are listings —
 * {@code text/*}, XHTML and XML (+xml suffix) are accepted, anything
 * else (a PDF, an image) fails the scrape with an explicit reason.
 * Redirects are followed; non-2xx fails the scrape so the chain
 * records it.</p>
 *
 * <p>Charset (US-06.2), in order: the response header's charset
 * parameter (validated), then Tika's encoding detectors (BOM, metadata
 * declaration), then jsoup's own detection (HTML meta + UTF-8
 * default). tika-core intentionally carries no statistical detector —
 * the full UniversalEncodingDetector lives in the tika-parser-text
 * module with guava/commonmark baggage not worth it for a CLI; the
 * three-step chain covers the encodings common on the web.</p>
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

    /** Tika's default encoding chain (tika-core 4.x: BOM + metadata). */
    private static final EncodingDetector ENCODING_DETECTOR =
            new DefaultEncodingDetector();

    private static final MimeTypes MIME_TYPES = MimeTypes.getDefaultMimeTypes();

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
        return scrapeUrl(address.url().trim());
    }

    /**
     * Fetches any URL (US-12.4 pagination iteration) with the same
     * guarantees as {@link #scrape(Address)}: redirects followed,
     * non-2xx fails, streaming under the byte cap and the fetch
     * deadline, Tika content-type/charset handling, jsoup
     * normalization.
     */
    @Override
    public ScrapeResult scrapeUrl(String url) {
        long start = System.nanoTime();
        String trimmed = url == null ? "" : url.trim();
        // The failure/trace helpers take an Address for its URL; the
        // pagination iteration fetches URLs that are not registered
        // addresses (US-12.4).
        Address page = new Address("URL", null, null, trimmed,
                es.jllopez.neis.domain.model.AddressType.URL_NEWS);
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(trimmed))
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
                throw failure(page, start, " returned HTTP " + status);
            }
            String contentType = response.headers().firstValue("Content-Type")
                    .orElse(null);
            // Content-Length pre-flight: refuse before reading a byte.
            long declared = response.headers().firstValueAsLong("Content-Length")
                    .orElse(-1);
            if (declared > maxBytes) {
                closeQuietly(response.body());
                throw failure(page, start, " Content-Length " + declared
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
                body = readBody(page, stream, start, deadlineNanos);
            } finally {
                alarm.cancel(false);
            }
            // US-06.2 (issue #28): content type — the response header
            // decides; a missing or generic one is re-detected by Tika's
            // MIME registry from the bytes. Only text pages are
            // listings; anything else fails with an explicit reason.
            MediaType headerType = contentType == null
                    ? null : MediaType.parse(contentType);
            // The header's own charset parameter is read from the
            // ORIGINAL header type, so a charset on a generic bucket
            // (octet-stream; charset=...) is not lost to the
            // re-detection (review finding, 2026-11-14).
            Map<String, String> headerParams = headerType == null
                    ? Map.of() : headerType.getParameters();
            MediaType mediaType =
                    (headerType == null || isGenericBinary(headerType))
                            ? detectMediaType(body) : headerType;
            if (!isListable(mediaType)) {
                throw failure(page, start, " returned " + mediaType
                        + " — WEBSCRAPE fetches text/HTML listing pages");
            }
            // Charset, in order: the header's charset parameter
            // (validated), Tika's encoding detectors (BOM, metadata),
            // then jsoup's own detection (HTML meta + UTF-8 default).
            // Note: a body BOM always overrides any supplied charset —
            // jsoup's documented contract, and the right one (BOMs are
            // authoritative); the order below decides only when NO BOM
            // is present.
            Charset charset = null;
            String param = headerParams.get("charset");
            if (param != null) {
                charset = safeCharset(param);
            }
            if (charset == null) {
                charset = detectCharset(body);
            }
            // jsoup normalizes the markup to HTML5.
            Document doc = DataUtil.load(new ByteArrayInputStream(body),
                    charset == null ? null : charset.name(), trimmed);
            String html = doc.html();
            // sizeBytes follows the static connector's convention: the
            // byte size of the content actually stored (the normalized
            // HTML5), not the raw wire size (review finding, 2026-11-14).
            int storedBytes = html.getBytes(StandardCharsets.UTF_8).length;
            // The record keeps the PARSEABLE response header verbatim;
            // when the header was missing or junk (the accept decision
            // was made on the Tika-detected type), the detected type is
            // the truthful record (review finding, 2026-11-14).
            String recordedType = headerType != null
                    ? contentType : mediaType.toString();
            log.info("WEBSCRAPE fetch {} -> HTTP {} {} wire {} bytes, stored {} bytes ({} ms)",
                    trimmed, status, recordedType, body.length, storedBytes, elapsedMs(start));
            return new ScrapeResult(html, recordedType, storedBytes);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw failure(page, start, " interrupted", e);
        } catch (IOException e) {
            throw failure(page, start, " failed: " + e, e);
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

    /** Generic binary buckets that say nothing about the real content. */
    private static boolean isGenericBinary(MediaType type) {
        return "application".equals(type.getType())
                && ("octet-stream".equals(type.getSubtype())
                    || "binary".equals(type.getSubtype()));
    }

    /**
     * Re-detects the media type from the bytes (US-06.2): no name hint,
     * so only magic bytes and the text detector decide — a PDF or an
     * image is identified as such, not waved through as HTML. Any Tika
     * runtime failure degrades to the generic bucket (which the
     * isListable gate then rejects) — every failure stays on the
     * documented fail-closed path (review finding, 2026-11-14).
     */
    private MediaType detectMediaType(byte[] body) {
        try (var tin = org.apache.tika.io.TikaInputStream.get(body)) {
            MediaType detected = MIME_TYPES.detect(
                    tin, new Metadata(), new org.apache.tika.parser.ParseContext());
            return detected == null
                    ? MediaType.application("octet-stream") : detected;
        } catch (Exception e) { // IOException + any Tika runtime failure
            return MediaType.application("octet-stream");
        }
    }

    /** The text pages a listing can be: text/*, XHTML, any +xml. */
    private static boolean isListable(MediaType type) {
        if ("text".equals(type.getType())) {
            return true;
        }
        return "application".equals(type.getType())
                && (type.getSubtype().equals("xhtml+xml")
                    || type.getSubtype().equals("xml")
                    || type.getSubtype().endsWith("+xml"));
    }

    private static Charset safeCharset(String name) {
        try {
            return Charset.forName(name);
        } catch (RuntimeException e) {
            return null; // illegal/unknown header charset: fall through
        }
    }

    /**
     * Tika's encoding detection (BOM, metadata) — best-effort. Tika 4.x
     * returns a ranked list of results; the highest-confidence charset
     * wins, an empty list means "no opinion" (jsoup decides next). Any
     * Tika runtime failure also means "no opinion".
     */
    private static Charset detectCharset(byte[] body) {
        try (var tin = org.apache.tika.io.TikaInputStream.get(body)) {
            List<org.apache.tika.detect.EncodingResult> results =
                    ENCODING_DETECTOR.detect(
                            tin, new Metadata(),
                            new org.apache.tika.parser.ParseContext());
            return results.stream()
                    .map(org.apache.tika.detect.EncodingResult::getCharset)
                    .filter(java.util.Objects::nonNull)
                    .findFirst()
                    .orElse(null);
        } catch (Exception e) { // IOException + any Tika runtime failure
            if (carriesInterrupt(e)) {
                Thread.currentThread().interrupt();
            }
            return null; // jsoup decides next
        }
    }

    /** Tika may wrap an interrupt in a runtime exception: unwrapped. */
    private static boolean carriesInterrupt(Throwable t) {
        while (t != null) {
            if (t instanceof InterruptedException) {
                return true;
            }
            t = t.getCause();
        }
        return false;
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
