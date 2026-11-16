package es.jllopez.neis.infrastructure.adapter.verification;

import es.jllopez.neis.domain.model.Address;
import es.jllopez.neis.domain.model.VerificationOutcome;
import es.jllopez.neis.domain.model.VerificationStatus;
import es.jllopez.neis.domain.port.AddressVerificationPort;
import es.jllopez.neis.domain.service.WebpageTypeClassifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;

/**
 * Driven adapter implementing {@link AddressVerificationPort} over
 * java.net.http HttpClient (US-04.2, issue #22): NEIS's first live
 * network call. A HEAD request decides; some servers reject HEAD, so a
 * GET is the fallback. Redirects are followed; any 2xx final response
 * verifies the address.
 *
 * <p>One operational log line per verification (target, method used,
 * status, elapsed) — never a protocol dump.</p>
 */
@Component
public class HttpClientAddressVerificationConnector implements AddressVerificationPort {

    private static final Logger log =
            LoggerFactory.getLogger(HttpClientAddressVerificationConnector.class);

    private final HttpClient client;
    /** The verification probe deadline (request and watchdog). Volatile:
     * the US-16.5 edit path may update it while verifications run. */
    private volatile Duration timeout;
    private final WebpageTypeClassifier classifier = new WebpageTypeClassifier();

    /**
     * Force-closes the classification stream at the deadline: once the
     * 200 OK headers arrive, HttpRequest.timeout() is spent and the
     * body read has NO deadline on any JDK this project runs (the
     * JDK-8258397 class — the same threat the scrape connector's
     * watchdog bounds; fa42575 review blocking finding). Without it, a
     * server that stalls the body after the headers would hang the
     * verification forever. One daemon thread serves all verifications.
     */
    private final java.util.concurrent.ScheduledExecutorService watchdog =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "neis-verification-watchdog");
                t.setDaemon(true);
                return t;
            });

    public HttpClientAddressVerificationConnector(
            @Value("${neis.address.verification.timeout-seconds:10}") long timeoutSeconds) {
        this.timeout = Duration.ofSeconds(timeoutSeconds);
        this.client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(timeout)
                .build();
    }

    /**
     * Applies a persisted/live override of {@code
     * neis.address.verification.timeout-seconds} (US-16.5 edit path):
     * the request and watchdog deadline takes effect for every
     * subsequent verification. The CLIENT's connect timeout keeps its
     * boot value (the HttpClient is built once) — a changed connect
     * budget needs a restart, documented in the config section.
     */
    public void updateTimeout(Duration newTimeout) {
        if (newTimeout == null || newTimeout.isZero() || newTimeout.isNegative()) {
            throw new IllegalArgumentException(
                    "The verification timeout must be a positive duration, got: " + newTimeout);
        }
        this.timeout = newTimeout;
        log.info("VERIFICATION timeout override applied: {} s", newTimeout.toSeconds());
    }

    @Override
    public VerificationOutcome verify(Address address) {
        String checkedAt = Instant.now().toString();
        long start = System.nanoTime();
        String method = "HEAD";
        try {
            HttpResponse<Void> response = request(address, method, checkedAt);
            if (response.statusCode() == 405 || response.statusCode() == 501) {
                // Server refuses HEAD: retry with GET.
                method = "GET";
                response = request(address, method, checkedAt);
            }
            int status = response.statusCode();
            boolean verified = status >= 200 && status < 300;
            String webpageType = null;
            if (verified) {
                // US-06.5 (issue #71): the probe fetches a bounded body
                // sample and classifies the page into the webpage-type
                // taxonomy. Classification NEVER decides verification
                // and never fails it — an unclassifiable page simply
                // keeps the previously stored type.
                webpageType = classify(address, checkedAt);
            }
            log.info("Address verification {} -> {} {} ({} ms{})", address.url(),
                    verified ? "VERIFIED" : "FAILED", status, elapsedMs(start),
                    webpageType == null ? "" : ", type " + webpageType);
            return new VerificationOutcome(
                    verified ? VerificationStatus.VERIFIED : VerificationStatus.FAILED,
                    checkedAt, status,
                    verified ? "reachable (HTTP " + status + ")"
                            : "server responded HTTP " + status,
                    webpageType);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            String detail = "interrupted";
            log.info("Address verification {} -> FAILED ({}, {} ms)",
                    address.url(), detail, elapsedMs(start));
            return new VerificationOutcome(VerificationStatus.FAILED, checkedAt, 0, detail);
        } catch (Exception e) {
            String detail = e.getClass().getSimpleName()
                    + (e.getMessage() != null ? ": " + e.getMessage() : "");
            log.info("Address verification {} -> FAILED ({}, {} ms)",
                    address.url(), detail, elapsedMs(start));
            return new VerificationOutcome(VerificationStatus.FAILED, checkedAt, 0, detail);
        }
    }

    /**
     * The bounded GET behind the classification (US-06.5): a streamed
     * body sample capped well below the scrape caps — framework
     * bootstrap markers sit in the first payload bytes. Any failure
     * yields null (unclassified), never a failed verification.
     */
    private String classify(Address address, String checkedAt) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(address.url().trim()))
                    .GET()
                    .timeout(timeout)
                    .header("User-Agent", "NEIS-verification/0.1")
                    .header("Accept", "text/html, */*")
                    .build();
            HttpResponse<java.io.InputStream> response = client.send(request,
                    HttpResponse.BodyHandlers.ofInputStream());
            // US-06.10 review finding: the status was only logged. A
            // non-2xx classification GET is an error page, never the
            // address's listing — classifying it could overwrite the
            // stored type. Refuse it: unclassified keeps the previously
            // stored type, the same contract as any classification
            // failure.
            int classifyStatus = response.statusCode();
            if (classifyStatus < 200 || classifyStatus >= 300) {
                log.info("Address classification {} -> unclassified (HTTP {} "
                                + "— the classification GET returned an error page; "
                                + "the stored type is kept)",
                        address.url(), classifyStatus);
                return null;
            }
            try (java.io.InputStream body = response.body()) {
                String contentType = response.headers().firstValue("Content-Type")
                        .orElse("");
                // The watchdog bounds the body read: a stalled body
                // surfaces as the close's IOException -> unclassified,
                // never a hung verification. The budget starts at the
                // read (the headers already consumed the request's
                // HTTP-level timeout).
                java.io.InputStream stream = body;
                java.util.concurrent.ScheduledFuture<?> alarm = watchdog.schedule(
                        () -> {
                            try {
                                stream.close();
                            } catch (java.io.IOException ignored) {
                                // the close IS the alarm firing
                            }
                        },
                        timeout.toNanos(),
                        java.util.concurrent.TimeUnit.NANOSECONDS);
                byte[] sample;
                try {
                    sample = body.readNBytes(CLASSIFY_SAMPLE_BYTES);
                } finally {
                    alarm.cancel(false);
                }
                String type = classifier.classify(contentType,
                        new String(sample, java.nio.charset.StandardCharsets.ISO_8859_1));
                log.info("Address classification {} -> {} (HTTP {}, {} bytes sampled)",
                        address.url(), type == null ? "unclassified" : type,
                        response.statusCode(), sample.length);
                return type;
            }
        } catch (Exception e) {
            log.info("Address classification {} -> unclassified ({})",
                    address.url(), e.getClass().getSimpleName());
            return null;
        }
    }

    /** Body-sample cap of the classification probe (bytes). */
    static final int CLASSIFY_SAMPLE_BYTES = 256 * 1024;

    private HttpResponse<Void> request(Address address, String method,
                                       String checkedAt) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(address.url().trim()))
                .method(method, HttpRequest.BodyPublishers.noBody())
                .timeout(timeout)
                .header("User-Agent", "NEIS-verification/0.1")
                .header("Accept", "text/html, */*")
                .build();
        return client.send(request, HttpResponse.BodyHandlers.discarding());
    }

    private long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
