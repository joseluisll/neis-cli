package es.jllopez.neis.infrastructure.adapter.scrape;

import es.jllopez.neis.domain.model.Address;
import es.jllopez.neis.domain.model.ContentRecord;
import es.jllopez.neis.domain.model.ScrapeResult;
import es.jllopez.neis.domain.port.ContentStorePort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * The CACHED scrape connector (US-06.14, issue #138): serves the most
 * recent successful scrape of the address FROM THE STORE — no network,
 * no fixture — so reprocessing (selector identification, date-aware
 * extraction per US-14.9/.11, filtering decisions) can run over
 * content already fetched.
 *
 * <p>Source of truth: the address's latest WEBSCRAPE {@code
 * listing-html} content record ({@code scrape.html} — the deterministic
 * per-address record whose write on every run makes it "the latest").
 * The record's {@code createdAt} is the ORIGINAL scrape time and rides
 * the result as the provenance note; the execution outcome, the log
 * line and the report carry it (never a silent staleness). Fail-closed
 * like every connector: an address with no stored content, a missing
 * file, or content older than the configured
 * {@code neis.scrape.cached-max-age} (US-06.15, #142 — default
 * unlimited, the plain US-06.14 semantics) fails the fetch loud.</p>
 *
 * <p>What it deliberately does NOT serve: pagination pages (only the
 * main listing is the retained scrape) and filter re-application
 * (filters are applied at fetch time; stored content predates them) —
 * both fail loud per the port defaults' contract.</p>
 */
@Component
public class CachedScrapeConnector
        implements es.jllopez.neis.domain.port.ContentScrapePort,
        org.springframework.beans.factory.DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(CachedScrapeConnector.class);

    private final ContentStorePort store;
    /** Null = unlimited (the US-06.14 default). */
    private final Duration maxAge;

    public CachedScrapeConnector(
            ContentStorePort store,
            @Value("${neis.scrape.cached-max-age:}") String maxAge) {
        this.store = store;
        this.maxAge = parseMaxAge(maxAge);
        if (this.maxAge != null) {
            log.info("CACHED scrape connector: staleness bound {} ("
                    + "neis.scrape.cached-max-age, US-06.15)", this.maxAge);
        }
    }

    /**
     * Parses the configured staleness bound (US-06.15): blank/unset =
     * unlimited (null); a value that does not parse as an ISO-8601
     * duration fails the boot — a typo must never silently disable the
     * bound. Zero/negative refused: a non-positive bound would make
     * every cached fetch fail.
     */
    static Duration parseMaxAge(String configured) {
        if (configured == null || configured.isBlank()) {
            return null;
        }
        Duration parsed;
        try {
            parsed = Duration.parse(configured.trim());
        } catch (java.time.format.DateTimeParseException e) {
            throw new IllegalArgumentException(
                    "neis.scrape.cached-max-age '" + configured
                            + "' is not a valid ISO-8601 duration (e.g. PT72H, P7D)"
                            + " (US-06.15)", e);
        }
        if (parsed.isZero() || parsed.isNegative()) {
            throw new IllegalArgumentException(
                    "neis.scrape.cached-max-age must be positive, got "
                            + configured + " (US-06.15)");
        }
        return parsed;
    }

    @Override
    public ScrapeResult scrape(Address address) {
        ContentRecord source = latestScrape(address);
        Instant scrapedAt = source.createdAt();
        // US-06.15 fail-closed edge (review finding): a legacy record
        // with no timestamp has UNKNOWN age — with a bound configured
        // that must fail loud, never pass as unlimited.
        if (maxAge != null && scrapedAt == null) {
            throw new IllegalStateException(
                    "The cached source of " + address.id() + " ("
                            + source.id() + ") carries no scrape timestamp, "
                            + "so its age cannot be checked against the "
                            + "configured bound " + maxAge
                            + " (neis.scrape.cached-max-age, US-06.15) — "
                            + "fetch live once to repopulate it");
        }
        if (maxAge != null && scrapedAt.plus(maxAge).isBefore(Instant.now())) {
            Duration age = Duration.between(scrapedAt, Instant.now());
            throw new IllegalStateException(
                    "Stored content of " + address.id() + " is too old for a "
                            + "cached fetch: scraped " + scrapedAt + " (age "
                            + age + "), configured bound "
                            + maxAge + " (neis.scrape.cached-max-age, US-06.15) "
                            + "— fetch live instead or raise the bound");
        }
        Path file = Path.of(source.path());
        if (!Files.isReadable(file)) {
            throw new IllegalStateException(
                    "The cached source of " + address.id() + " is gone: "
                            + file + " is not readable — fetch live to "
                            + "repopulate it (US-06.14)");
        }
        try {
            String html = Files.readString(file, StandardCharsets.UTF_8);
            Duration age = scrapedAt == null ? null
                    : Duration.between(scrapedAt, Instant.now());
            String note = "cached; original scrape " + scrapedAt
                    + (age == null ? "" : ", age " + age)
                    + " (" + source.id() + ")";
            log.info("SCRAPE {}: serving STORED content — {}", address.id(), note);
            return new ScrapeResult(html, "text/html; charset=UTF-8",
                    html.length(), note);
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Cannot read the cached source of " + address.id() + ": "
                            + file + " (US-06.14)", e);
        }
    }

    /**
     * The address's latest successful scrape: the newest WEBSCRAPE
     * listing-html content record (US-11.6's cache-source query —
     * exactly what the store port answers; a corrected URL's newer
     * capture wins by createdAt).
     */
    private ContentRecord latestScrape(Address address) {
        return store.findLatestWebpageScrape(address.id())
                .orElseThrow(() -> new IllegalStateException(
                        "No stored content for " + address.id()
                                + " — a cached fetch needs at least one prior "
                                + "successful scrape (US-06.14): fetch live once, "
                                + "then the cached mode serves it"));
    }

    /**
     * Whether any scrape is stored for the address (US-06.12,
     * issue #134): the router's fetchability warning consults this —
     * an address with stored content is fetchable under every
     * no-network policy.
     */
    public boolean hasStored(String addressId) {
        return store.findLatestWebpageScrape(addressId).isPresent();
    }

    @Override
    public String toString() {
        return "CachedScrapeConnector(maxAge=" + maxAge + ")";
    }

    /** No resources held — the pool-free connector has nothing to close. */
    @Override
    public void destroy() {
        // no-op: stateless over the store
    }
}
