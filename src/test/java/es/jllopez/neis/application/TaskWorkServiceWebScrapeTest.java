package es.jllopez.neis.application;

import es.jllopez.neis.domain.model.Address;
import es.jllopez.neis.domain.model.AddressType;
import es.jllopez.neis.domain.model.StoredSelectors;
import es.jllopez.neis.domain.port.SelectorStorePort;
import es.jllopez.neis.domain.service.PaginationDetector;
import es.jllopez.neis.domain.service.UrlSyntaxValidator;
import es.jllopez.neis.infrastructure.adapter.scrape.JsoupSelectorConnector;
import es.jllopez.neis.infrastructure.adapter.scrape.JsoupSimplifyConnector;
import es.jllopez.neis.infrastructure.adapter.scrape.StaticScrapeConnector;
import es.jllopez.neis.testsupport.InMemoryAddressCatalogPort;
import es.jllopez.neis.testsupport.InMemoryContentStorePort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WEBSCRAPE item extraction (US-06.4, issue #44): with a preconfigured
 * selector the step extracts the listing's news/events items with their
 * metadata into an items.json artifact — deterministic, no AI; without
 * one, extraction is skipped with an explicit note (identifying a
 * selector is ANALYZE's job).
 */
class TaskWorkServiceWebScrapeTest {

    private final AddressCatalogService catalog = new AddressCatalogService(
            new InMemoryAddressCatalogPort(List.of(new Address("ADDR-001", "News",
                    null, "https://commission.europa.eu/news-and-media/news_en",
                    AddressType.URL_NEWS))),
            new UrlSyntaxValidator());

    @TempDir
    Path workDir;

    private TaskWorkService service(PreconfiguredSelectors preconfigured) {
        return service(preconfigured, null);
    }

    private TaskWorkService service(PreconfiguredSelectors preconfigured,
                                    String configuredMaxItems) {
        PipelineService pipeline = new PipelineService(new StaticScrapeConnector(),
                new JsoupSimplifyConnector(), new JsoupSelectorConnector(),
                new SelectorStorePort() {
                    @Override
                    public Optional<StoredSelectors> load(String addressId) {
                        return Optional.empty();
                    }

                    @Override
                    public void save(StoredSelectors selectors) {
                        // not exercised by the WEBSCRAPE step
                    }
                },
                new PaginationDetector(),
                new es.jllopez.neis.domain.service.ExposedFilterDetector(),
                new ObjectProviderStub(), catalog, new UrlSyntaxValidator(),
                preconfigured,
                new ContentStoreService(new InMemoryContentStorePort(), workDir.toString()),
                workDir.toString());
        return new TaskWorkService(pipeline, catalog, configuredMaxItems);
    }

    @BeforeEach
    void cleanWorkDir() throws Exception {
        // content folders are deterministic per URL; start empty
        if (Files.exists(workDir)) {
            try (var paths = Files.list(workDir)) {
                for (Path p : paths.toList()) {
                    if (Files.isDirectory(p)) {
                        try (var walk = Files.walk(p)) {
                            walk.sorted(java.util.Comparator.reverseOrder())
                                    .forEach(q -> q.toFile().delete());
                        }
                    } else {
                        Files.delete(p);
                    }
                }
            }
        }
    }

    @Test
    void extractsItemsWithAPreconfiguredSelector() {
        PreconfiguredSelectors configured = new PreconfiguredSelectors(
                new PreconfiguredSelectorProperties(List.of(
                        new PreconfiguredSelectorProperties.Entry("ADDR-001", "news",
                                "ul.news-list li"))));

        String outcome = service(configured).execute("WEBSCRAPE",
                catalog.byId("ADDR-001"));

        assertTrue(outcome.contains("items extracted: "), outcome);
        int count = Integer.parseInt(outcome.replaceAll(
                ".*items extracted: (\\d+).*", "$1"));
        assertTrue(count > 0, "the ADDR-001 fixture yields items: " + outcome);
        // The items.json artifact exists next to scrape.html and the
        // outcome names the selector.
        assertTrue(Files.exists(firstFileMatching("items.json")), outcome);
        assertTrue(outcome.contains("ul.news-list li"), outcome);
    }

    @Test
    void skipsExtractionWithoutAPreconfiguredSelector() {
        String outcome = service(new PreconfiguredSelectors(
                new PreconfiguredSelectorProperties(List.of()))).execute("WEBSCRAPE",
                catalog.byId("ADDR-001"));

        assertTrue(outcome.contains("no preconfigured selector"), outcome);
        assertFalse(Files.exists(firstFileMatching("items.json")),
                "no items.json without a selector");
    }

    @Test
    void extractedItemsCarryTheMetadata() throws Exception {
        PreconfiguredSelectors configured = new PreconfiguredSelectors(
                new PreconfiguredSelectorProperties(List.of(
                        new PreconfiguredSelectorProperties.Entry("ADDR-001", "news",
                                "ul.news-list li"))));
        service(configured).execute("WEBSCRAPE", catalog.byId("ADDR-001"));

        String json = Files.readString(firstFileMatching("items.json"));
        assertTrue(json.startsWith("[") && json.endsWith("]"), json);
        assertTrue(json.contains("\"title\""), json);
        assertTrue(json.contains("\"url\""), json);
        // Absolute detail URLs (base URI = the address URL).
        assertTrue(json.contains("https://commission.europa.eu/"), json);
    }

    @Test
    void maxItemsParameterCapsTheExtraction() {
        // US-12.5: extraction stops at the limit without error; the
        // outcome records how many were extracted and that the cap was
        // reached.
        PreconfiguredSelectors configured = new PreconfiguredSelectors(
                new PreconfiguredSelectorProperties(List.of(
                        new PreconfiguredSelectorProperties.Entry("ADDR-001", "news",
                                "ul.news-list li"))));

        String outcome = service(configured, null).execute("WEBSCRAPE",
                catalog.byId("ADDR-001"), java.util.Map.of("max-items", "3"));

        assertTrue(outcome.contains("items extracted: 3"), outcome);
        assertTrue(outcome.contains("cap 3, reached"), outcome);
    }

    @Test
    void configuredMaxItemsIsTheDefaultCap() {
        // US-12.5: the externalized default applies when the task
        // carries no max-items parameter.
        PreconfiguredSelectors configured = new PreconfiguredSelectors(
                new PreconfiguredSelectorProperties(List.of(
                        new PreconfiguredSelectorProperties.Entry("ADDR-001", "news",
                                "ul.news-list li"))));

        String outcome = service(configured, "5").execute("WEBSCRAPE",
                catalog.byId("ADDR-001"));

        assertTrue(outcome.contains("items extracted: 5"), outcome);
        assertTrue(outcome.contains("cap 5, reached"), outcome);
    }

    @Test
    void withoutCapConfigurationExtractionIsUnlimited() {
        PreconfiguredSelectors configured = new PreconfiguredSelectors(
                new PreconfiguredSelectorProperties(List.of(
                        new PreconfiguredSelectorProperties.Entry("ADDR-001", "news",
                                "ul.news-list li"))));

        String noParam = service(configured, null).execute("WEBSCRAPE",
                catalog.byId("ADDR-001"));
        String explicitHuge = service(configured, null).execute("WEBSCRAPE",
                catalog.byId("ADDR-001"), java.util.Map.of("max-items", "10000"));

        assertTrue(noParam.contains("no cap"), noParam);
        assertTrue(explicitHuge.contains("cap 10000"), explicitHuge);
        // The ADDR-001 fixture yields 15 items: the full listing is
        // extracted under both.
        int all = Integer.parseInt(noParam.replaceAll(".*items extracted: (\\d+).*", "$1"));
        int huge = Integer.parseInt(explicitHuge.replaceAll(".*items extracted: (\\d+).*", "$1"));
        assertEquals(all, huge);
        assertTrue(all > 5, "the uncapped listing exceeds the capped runs: " + all);
    }

    @Test
    void invalidConfiguredMaxItemsFailsTheBoot() {
        PreconfiguredSelectors configured = new PreconfiguredSelectors(
                new PreconfiguredSelectorProperties(List.of(
                        new PreconfiguredSelectorProperties.Entry("ADDR-001", "news",
                                "ul.news-list li"))));
        IllegalArgumentException letters = assertThrows(IllegalArgumentException.class,
                () -> service(configured, "many"));
        assertTrue(letters.getMessage().contains("must be an integer"));
        IllegalArgumentException zero = assertThrows(IllegalArgumentException.class,
                () -> service(configured, "0"));
        assertTrue(zero.getMessage().contains(">= 1"));
    }

    private Path firstFileMatching(String fileName) {
        try (var walk = Files.walk(workDir)) {
            return walk.filter(p -> p.getFileName().toString().equals(fileName))
                    .findFirst().orElse(workDir.resolve("missing-" + fileName));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** No AI: the WEBSCRAPE step never touches the assistant. */
    private static final class ObjectProviderStub
            implements org.springframework.beans.factory.ObjectProvider<es.jllopez.neis.domain.port.AiAssistantPort> {
        @Override
        public es.jllopez.neis.domain.port.AiAssistantPort getObject() {
            throw new IllegalStateException("no AI in this test");
        }

        @Override
        public es.jllopez.neis.domain.port.AiAssistantPort getIfAvailable() {
            return null;
        }
    }
}
