package es.jllopez.neis.infrastructure.adapter.verification;

import es.jllopez.neis.domain.model.Address;
import es.jllopez.neis.domain.model.VerificationOutcome;
import es.jllopez.neis.domain.model.VerificationStatus;
import es.jllopez.neis.domain.port.AddressVerificationPort;
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
    private final Duration timeout;

    public HttpClientAddressVerificationConnector(
            @Value("${neis.address.verification.timeout-seconds:10}") long timeoutSeconds) {
        this.timeout = Duration.ofSeconds(timeoutSeconds);
        this.client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(timeout)
                .build();
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
            log.info("Address verification {} -> {} {} ({} ms)", address.url(),
                    verified ? "VERIFIED" : "FAILED", status, elapsedMs(start));
            return new VerificationOutcome(
                    verified ? VerificationStatus.VERIFIED : VerificationStatus.FAILED,
                    checkedAt, status,
                    verified ? "reachable (HTTP " + status + ")"
                            : "server responded HTTP " + status);
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
