package es.jllopez.neis.infrastructure.adapter.scrape;

import es.jllopez.neis.domain.model.Address;
import es.jllopez.neis.domain.model.AddressType;
import es.jllopez.neis.domain.model.ContentRecord;
import es.jllopez.neis.domain.model.ScrapeResult;
import es.jllopez.neis.domain.port.ContentStorePort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * US-06.14 (issue #138) + US-06.15 (issue #142): the cached connector
 * serves the address's latest WEBSCRAPE listing from the store with
 * honest provenance, and fails closed when there is nothing to serve,
 * the file is gone, or the content is older than the configured bound.
 */
class CachedScrapeConnectorTest {

    private static final Address ADDRESS = new Address("ADDR-010",
            "ABC News", "https://www.abc.es", AddressType.URL_NEWS);

    @TempDir
    Path dir;

    private ContentRecord storedScrape(String contentId, String fileName,
                                       Instant createdAt) {
        Path file = dir.resolve(fileName);
        try {
            Files.writeString(file, "<html>stored listing</html>");
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
        return new ContentRecord(contentId, ADDRESS.id(), "cap-abc",
                "WEBSCRAPE", "listing-html", file.toString(), createdAt, null);
    }

    /** Store double: exactly the port surface the connector uses. */
    private static class StubStore implements ContentStorePort {
        final List<ContentRecord> records;

        StubStore(ContentRecord... records) {
            this.records = List.of(records);
        }

        @Override
        public es.jllopez.neis.domain.model.Capture saveCapture(
                es.jllopez.neis.domain.model.Capture capture) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<es.jllopez.neis.domain.model.Capture> findCapture(
                String addressId, String captureId) {
            return Optional.empty();
        }

        @Override
        public ContentRecord saveContent(ContentRecord record) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<ContentRecord> findAll() {
            return records;
        }

        @Override
        public List<ContentRecord> findByAddress(String addressId) {
            // newest-first like the real connector
            return records.stream()
                    .filter(r -> r.addressId().equals(addressId))
                    .toList();
        }

        @Override
        public List<ContentRecord> findByExecution(String executionId) {
            return List.of();
        }

        @Override
        public Optional<ContentRecord> findById(String id) {
            return records.stream()
                    .filter(r -> r.id().equals(id)).findFirst();
        }
    }

    @Test
    void servesTheLatestStoredScrapeWithProvenance() {
        ContentRecord record = storedScrape("CONT-abc", "scrape.html",
                Instant.parse("2026-11-19T10:00:00Z"));
        CachedScrapeConnector connector = new CachedScrapeConnector(
                new StubStore(record), "");

        ScrapeResult result = connector.scrape(ADDRESS);

        assertThat(result.html()).contains("stored listing");
        assertThat(result.contentType()).isEqualTo("text/html; charset=UTF-8");
        assertThat(result.sourceNote())
                .contains("cached")
                .contains("2026-11-19T10:00:00Z")
                .contains("CONT-abc");
    }

    @Test
    void noStoredContentFailsClosed() {
        CachedScrapeConnector connector = new CachedScrapeConnector(
                new StubStore(), "");

        assertThatThrownBy(() -> connector.scrape(ADDRESS))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No stored content")
                .hasMessageContaining("ADDR-010")
                .hasMessageContaining("US-06.14");
    }

    @Test
    void nonWebscrapeRecordsAreNotServed() {
        ContentRecord simplify = new ContentRecord("CONT-s", ADDRESS.id(),
                "cap-abc", "SIMPLIFY", "simplified-html",
                dir.resolve("simplified.html").toString(),
                Instant.now(), null);
        CachedScrapeConnector connector = new CachedScrapeConnector(
                new StubStore(simplify), "");

        assertThatThrownBy(() -> connector.scrape(ADDRESS))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No stored content");
    }

    @Test
    void aGoneSourceFileFailsLoud() {
        ContentRecord record = new ContentRecord("CONT-gone", ADDRESS.id(),
                "cap-abc", "WEBSCRAPE", "listing-html",
                dir.resolve("missing.html").toString(), Instant.now(), null);
        CachedScrapeConnector connector = new CachedScrapeConnector(
                new StubStore(record), "");

        assertThatThrownBy(() -> connector.scrape(ADDRESS))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("gone")
                .hasMessageContaining("US-06.14");
    }

    @Test
    void contentOlderThanTheBoundFailsNamingAgeAndBound() {
        ContentRecord old = storedScrape("CONT-old", "scrape.html",
                Instant.now().minus(Duration.ofDays(30)));
        CachedScrapeConnector connector = new CachedScrapeConnector(
                new StubStore(old), "PT72H");

        assertThatThrownBy(() -> connector.scrape(ADDRESS))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("too old")
                .hasMessageContaining("PT72H")
                .hasMessageContaining("US-06.15");
    }

    @Test
    void contentWithinTheBoundIsServed() {
        ContentRecord recent = storedScrape("CONT-new", "scrape.html",
                Instant.now().minus(Duration.ofHours(1)));
        CachedScrapeConnector connector = new CachedScrapeConnector(
                new StubStore(recent), "PT72H");

        assertThat(connector.scrape(ADDRESS).html()).contains("stored listing");
    }

    @Test
    void paginationAndArbitraryUrlsAreRefused() {
        CachedScrapeConnector connector = new CachedScrapeConnector(
                new StubStore(), "");

        assertThatThrownBy(() -> connector.scrape(ADDRESS, 1))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> connector.scrapeUrl("https://example.eu/page2"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> connector.scrapeFiltered(ADDRESS, "date"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> connector.scrapeWithFilters(ADDRESS,
                        java.util.Map.of("section", "news"), null))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(connector.supportedFilterRequests(ADDRESS)).isEmpty();
    }

    @Test
    void maxAgeParsingRejectsTyposAndNonPositiveValues() {
        assertThat(CachedScrapeConnector.parseMaxAge("")).isNull();
        assertThat(CachedScrapeConnector.parseMaxAge("  ")).isNull();
        assertThat(CachedScrapeConnector.parseMaxAge("PT72H"))
                .isEqualTo(Duration.ofHours(72));
        assertThatThrownBy(() -> CachedScrapeConnector.parseMaxAge("72h"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("US-06.15");
        assertThatThrownBy(() -> CachedScrapeConnector.parseMaxAge("PT0S"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive");
        assertThatThrownBy(() -> CachedScrapeConnector.parseMaxAge("-PT1H"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive");
    }
}
