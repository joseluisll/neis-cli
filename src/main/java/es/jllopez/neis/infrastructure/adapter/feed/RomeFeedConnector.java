package es.jllopez.neis.infrastructure.adapter.feed;

import es.jllopez.neis.domain.model.Address;
import es.jllopez.neis.domain.model.NewsEventItem;
import es.jllopez.neis.domain.port.FeedSourcePort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Driven adapter implementing {@link FeedSourcePort} over Rome
 * (US-06.8, issue #86): the fetch keeps the HTTP scrape connector's
 * misbehaving-server discipline (request timeout, declared-size
 * refusal, streamed body cap, watchdog force-close — the JDK-8258397
 * class), and the parse maps Rome's normalized entries into standard
 * news/event records. ONE operational log line per fetch (url, bytes,
 * elapsed, entry count) — never document bodies.
 */
@Component
public class RomeFeedConnector implements FeedSourcePort {

    private static final Logger log = LoggerFactory.getLogger(RomeFeedConnector.class);

    private final HttpClient client;
    private final Duration timeout;
    private final int maxBytes;

    /** Force-closes the response stream at the deadline (see fetch()). */
    private final ScheduledExecutorService watchdog =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "neis-feed-watchdog");
                t.setDaemon(true);
                return t;
            });

    public RomeFeedConnector(
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
    public byte[] fetch(Address address) {
        long start = System.nanoTime();
        String url = address.url().trim();
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .GET()
                    .timeout(timeout)
                    .header("User-Agent", "NEIS-feed/0.1")
                    .header("Accept",
                            "application/rss+xml, application/atom+xml, application/xml, text/xml, */*")
                    .build();
            HttpResponse<InputStream> response = client.send(request,
                    HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                // With BodyHandlers.ofInputStream the connection stays
                // pinned until the stream is closed: release it on the
                // non-2xx path too (review finding 2026-11-15).
                closeQuietly(response.body());
                throw new IllegalStateException("Feed fetch " + url
                        + " returned HTTP " + response.statusCode());
            }
            response.headers().firstValue("Content-Length")
                    .map(RomeFeedConnector::parseDeclaredLength)
                    .filter(declared -> declared > maxBytes)
                    .ifPresent(declared -> {
                        closeQuietly(response.body());
                        throw new IllegalStateException("Feed fetch " + url
                                + " Content-Length " + declared
                                + " exceeds the " + maxBytes + " byte cap");
                    });
            // The body read has no deadline of its own (JDK-8258397
            // class): the watchdog force-closes the stream at the
            // deadline, bounding a stalled server.
            ScheduledFuture<?> alarm = watchdog.schedule(
                    () -> closeQuietly(response.body()),
                    timeout.toNanos(), TimeUnit.NANOSECONDS);
            byte[] body;
            try (InputStream stream = response.body()) {
                body = stream.readNBytes(maxBytes + 1);
            } finally {
                alarm.cancel(false);
            }
            if (body.length > maxBytes) {
                throw new IllegalStateException("Feed fetch " + url
                        + " exceeds the " + maxBytes + " byte cap");
            }
            log.info("FEED fetch {} -> {} bytes ({} ms)", url, body.length,
                    (System.nanoTime() - start) / 1_000_000);
            return body;
        } catch (RuntimeException e) {
            log.info("FEED fetch {} -> FAILED ({} , {} ms)", url,
                    e.getClass().getSimpleName(),
                    (System.nanoTime() - start) / 1_000_000);
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Feed fetch " + url + " interrupted", e);
        } catch (Exception e) {
            throw new IllegalStateException("Feed fetch " + url + " failed: "
                    + e.getMessage(), e);
        }
    }

    @Override
    public List<NewsEventItem> parse(byte[] feedXml, String addressId,
                                     String itemKind, int maxItems, String baseUrl) {
        if (feedXml == null || feedXml.length == 0) {
            throw new IllegalArgumentException("The feed document of " + addressId
                    + " is empty — nothing to parse");
        }
        // Rome's XmlReader detects the charset from the BOM and the
        // XML declaration — the byte document is handed over verbatim
        // so a non-UTF-8 feed (ISO-8859-1 etc.) parses correctly.
        try (var reader = new com.rometools.rome.io.XmlReader(
                new java.io.ByteArrayInputStream(feedXml))) {
            var feed = new com.rometools.rome.io.SyndFeedInput().build(reader);
            return parse(feed, addressId, itemKind, maxItems, baseUrl);
        } catch (Exception e) {
            throw new IllegalStateException("The feed document of " + addressId
                    + " does not parse as a feed: " + e.getMessage(), e);
        }
    }

    @Override
    public String toDocumentText(byte[] feedBytes) {
        try (var reader = new com.rometools.rome.io.XmlReader(
                new java.io.ByteArrayInputStream(feedBytes))) {
            String encoding = reader.getEncoding();
            Charset charset = encoding != null
                    ? Charset.forName(encoding)
                    : StandardCharsets.UTF_8;
            return new String(feedBytes, charset);
        } catch (Exception e) {
            // Undecodable for display: fall back to the lossless
            // representation — artifacts are about evidence, and the
            // parse path reports its own errors.
            return new String(feedBytes, StandardCharsets.UTF_8);
        }
    }

    /** Visible for tests: parse an already-loaded Rome feed. */
    List<NewsEventItem> parse(com.rometools.rome.feed.synd.SyndFeed feed,
                              String addressId, String itemKind, int maxItems,
                              String baseUrl) {
        List<NewsEventItem> items = new ArrayList<>();
        for (com.rometools.rome.feed.synd.SyndEntry entry : feed.getEntries()) {
            if (items.size() >= maxItems) {
                break;
            }
            items.add(toItem(entry, addressId, itemKind, baseUrl));
        }
        return items;
    }

    /**
     * Rome entry -> standard record: title, description (or the first
     * content block), link and the newest of the published/updated
     * dates as ISO-8601. An entry without a title or link is still
     * mapped — the store flags its completeness (US-14.4), nothing is
     * silently dropped.
     */
    private NewsEventItem toItem(com.rometools.rome.feed.synd.SyndEntry entry,
                                 String addressId, String itemKind, String baseUrl) {
        String description = entry.getDescription() != null
                ? entry.getDescription().getValue()
                : (entry.getContents() != null && !entry.getContents().isEmpty()
                        ? entry.getContents().get(0).getValue() : null);
        java.util.Date date = entry.getPublishedDate() != null
                ? entry.getPublishedDate() : entry.getUpdatedDate();
        return new NewsEventItem(addressId, itemKind,
                entry.getTitle(),
                description,
                entry.getLink() != null ? entry.getLink() : null,
                date != null ? DateTimeFormatter.ISO_LOCAL_DATE.format(
                        date.toInstant().atZone(ZoneOffset.UTC).toLocalDate()) : null);
    }

    /**
     * Parses a declared Content-Length header: unparseable (broken or
     * hostile) values are treated as absent so the streamed-read cap
     * below stays the authority — a raw NumberFormatException here
     * would also bypass the body close.
     */
    private static Long parseDeclaredLength(String value) {
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static void closeQuietly(InputStream stream) {
        try {
            stream.close();
        } catch (Exception ignored) {
            // the close IS the watchdog firing
        }
    }
}
