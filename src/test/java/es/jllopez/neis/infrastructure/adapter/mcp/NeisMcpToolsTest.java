package es.jllopez.neis.infrastructure.adapter.mcp;

import es.jllopez.neis.application.AddressCatalogService;
import es.jllopez.neis.application.AddressVerificationService;
import es.jllopez.neis.application.ContentStoreService;
import es.jllopez.neis.application.ManagementInfoService;
import es.jllopez.neis.application.NewsEventService;
import es.jllopez.neis.application.RemoteJobService;
import es.jllopez.neis.application.SettingsService;
import es.jllopez.neis.application.SystemInfoService;
import es.jllopez.neis.domain.model.Address;
import es.jllopez.neis.domain.model.AddressType;
import es.jllopez.neis.domain.model.ContentRecord;
import es.jllopez.neis.domain.model.JobStatus;
import es.jllopez.neis.domain.model.NewsEventItem;
import es.jllopez.neis.domain.model.NewsEventQuery;
import es.jllopez.neis.domain.model.RemoteJob;
import es.jllopez.neis.domain.model.VerificationOutcome;
import es.jllopez.neis.domain.model.VerificationStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The 10-tool catalog's mapping contract (US-18.4, issue #119): every
 * tool is parse → use case → typed result, no logic. The no-AI rule is
 * pinned negatively: no tool touches the AI integration point.
 */
class NeisMcpToolsTest {

    private final SystemInfoService system = mock(SystemInfoService.class);
    private final ManagementInfoService management =
            mock(ManagementInfoService.class);
    private final SettingsService settings = mock(SettingsService.class);
    private final AddressCatalogService catalog =
            mock(AddressCatalogService.class);
    private final AddressVerificationService verification =
            mock(AddressVerificationService.class);
    private final RemoteJobService jobs = mock(RemoteJobService.class);
    private final NewsEventService news = mock(NewsEventService.class);
    private final ContentStoreService content = mock(ContentStoreService.class);

    private NeisMcpTools tools() {
        return new NeisMcpTools(system, management, settings, catalog,
                verification, jobs, news, content);
    }

    private Address address(String id) {
        return new Address(id, "Name " + id, null, "https://example.eu/" + id,
                AddressType.URL_NEWS, Set.of(),
                VerificationStatus.VERIFIED, "2026-11-19T00:00:00Z", null);
    }

    private NewsEventItem item(String canonicalId) {
        return new NewsEventItem("ADDR-001", "news", "Title",
                "Description", "https://example.eu/item", "Jul 16, 2026",
                null, null, "EXEC-1", "CONT-1", false, null, canonicalId, null);
    }

    @Test
    void listAddressesReturnsTheCatalogAndFiltersToOne() {
        when(catalog.list()).thenReturn(List.of(address("ADDR-001"),
                address("ADDR-002")));
        when(catalog.byId("ADDR-001")).thenReturn(address("ADDR-001"));
        NeisMcpTools tools = tools();

        assertEquals(2, tools.listAddresses(null).total());
        var one = tools.listAddresses("ADDR-001");
        assertEquals(1, one.total());
        assertEquals("ADDR-001", one.addresses().get(0).id());
        // unknown id: the use case's own refusal propagates
        when(catalog.byId("ADDR-NOPE")).thenThrow(
                new IllegalArgumentException("Unknown address id: ADDR-NOPE"));
        assertThrows(IllegalArgumentException.class,
                () -> tools.listAddresses("ADDR-NOPE"));
    }

    @Test
    void upsertAddressRegistersWithoutIdAndUpdatesWithId() {
        when(catalog.create(eq(null), eq("New"), eq("desc"),
                eq("https://example.eu/new"), eq(AddressType.URL_NEWS)))
                .thenReturn(address("ADDR-009"));
        when(catalog.update(eq("ADDR-001"), eq("New"), eq("desc"),
                eq("https://example.eu/new"), eq(AddressType.URL_NEWS)))
                .thenReturn(address("ADDR-001"));
        NeisMcpTools tools = tools();

        assertEquals("ADDR-009", tools.upsertAddress(null, "New",
                "https://example.eu/new", "URL_NEWS", "desc").id());
        assertEquals("ADDR-001", tools.upsertAddress("ADDR-001", "New",
                "https://example.eu/new", "URL_NEWS", "desc").id());
        // an unknown type is refused before any use-case call
        assertThrows(IllegalArgumentException.class,
                () -> tools.upsertAddress(null, "x", "https://example.eu/x",
                        "WAT", null));
    }

    @Test
    void removeAddressReturnsTheRemovedAddress() {
        when(catalog.remove("ADDR-001")).thenReturn(address("ADDR-001"));
        assertEquals("ADDR-001", tools().removeAddress("ADDR-001").id());
    }

    @Test
    void verifyAddressReturnsTheProbeOutcome() {
        when(verification.verify("ADDR-001")).thenReturn(new VerificationOutcome(
                VerificationStatus.VERIFIED, "2026-11-19T00:00:00Z",
                200, "reachable"));
        assertEquals(VerificationStatus.VERIFIED,
                tools().verifyAddress("ADDR-001").status());
    }

    @Test
    void fetchNewsSubmitsTheJobAndReportsTheTargets() {
        RemoteJob job = new RemoteJob("JOB-1", "fetch-news", null,
                Map.of("chain-id", "CHAIN-1", "addresses", "ADDR-001,ADDR-002"),
                JobStatus.QUEUED, Instant.now(), null, null, null, null, null);
        when(jobs.submitFetchNews(null, null)).thenReturn(job);
        var accepted = tools().fetchNews(null, null);
        assertEquals("JOB-1", accepted.jobId());
        assertEquals(List.of("ADDR-001", "ADDR-002"), accepted.addresses());
    }

    @Test
    void jobStatusReturnsTheJobOrRefusesUnknownIds() {
        RemoteJob job = new RemoteJob("JOB-1", "run-task", "ADDR-001",
                Map.of(), JobStatus.COMPLETED, Instant.now(),
                Instant.now(), Instant.now(), "EXEC-1", "result", null);
        when(jobs.job("JOB-1")).thenReturn(java.util.Optional.of(job));
        when(jobs.job("JOB-nope")).thenReturn(java.util.Optional.empty());
        NeisMcpTools tools = tools();
        assertEquals("COMPLETED", tools.jobStatus("JOB-1").status().name());
        assertThrows(NoSuchElementException.class,
                () -> tools.jobStatus("JOB-nope"));
    }

    @Test
    void listNewsCombinesTheFreeTextAndFilterPaths() {
        NewsEventQuery addressKind = new NewsEventQuery("ADDR-001", "news",
                null, null, NewsEventQuery.DEFAULT_LIMIT);
        NewsEventQuery textFilters = new NewsEventQuery(null, "news",
                null, null, NewsEventQuery.DEFAULT_LIMIT);
        when(news.search(addressKind)).thenReturn(List.of(item("can-1")));
        when(news.search("environment", textFilters))
                .thenReturn(List.of(item("can-2")));
        NeisMcpTools tools = tools();

        assertEquals(1, tools.listNews("ADDR-001", "news", null, null,
                null, null, null).total());
        assertEquals("can-2", tools.listNews(null, "news", null, null,
                "environment", null, null).items().get(0).canonicalId());
    }

    @Test
    void listNewsPaginatesWithOffset() {
        // US-18.9 finding 6: the query tools paginate — offset skips
        // into the result, the page never exceeds the limit, and the
        // total reflects the matched set (not the page).
        NewsEventQuery page2 = new NewsEventQuery("ADDR-001", "news",
                null, null, 22);
        List<NewsEventItem> twentyTwo = new java.util.ArrayList<>();
        for (int i = 0; i < 22; i++) {
            twentyTwo.add(item("can-" + i));
        }
        when(news.search(page2)).thenReturn(twentyTwo);
        NeisMcpTools tools = tools();

        NeisMcpTools.NewsListResult result = tools.listNews("ADDR-001",
                "news", null, null, null, 20, 2);
        assertEquals(20, result.items().size(), "the page is the limit");
        assertEquals("can-2", result.items().get(0).canonicalId(),
                "offset 2 skips the first two");
        assertEquals(22, result.total(), "the total is the matched set");
    }

    @Test
    void getNewsItemMergesTheItemWithItsSightingHistory() {
        NewsEventItem stored = item("can-1");
        when(news.item("can-1")).thenReturn(java.util.Optional.of(stored));
        when(news.item("can-nope")).thenReturn(java.util.Optional.empty());
        when(news.sightings("can-1")).thenReturn(List.of(
                new es.jllopez.neis.domain.model.NewsEventSighting(
                        "row-1", "ADDR-001", "CONT-1", "EXEC-1",
                        Instant.now())));
        NeisMcpTools tools = tools();

        var detail = tools.getNewsItem("can-1");
        assertEquals("can-1", detail.item().canonicalId());
        // the merged sighting history comes from the store, not the item
        assertEquals(1, detail.sightings().size());
        assertEquals("ADDR-001", detail.sightings().get(0).addressId());
        assertThrows(NoSuchElementException.class,
                () -> tools.getNewsItem("can-nope"));
    }

    @Test
    void getContentFiltersByExecutionAndAddressAndRefusesUnknownIds() {
        ContentRecord fromExec1 = new ContentRecord("CONT-1", "ADDR-001",
                "cap-1", "PRINT", "report", "p/report.txt",
                Instant.now(), "EXEC-1");
        ContentRecord fromExec2 = new ContentRecord("CONT-2", "ADDR-002",
                "cap-2", "PRINT", "report", "p/report2.txt",
                Instant.now(), "EXEC-2");
        // use-case queries, not listAll (US-18.4 review)
        when(content.listByExecution("EXEC-1")).thenReturn(List.of(fromExec1));
        when(content.listByAddress("ADDR-002")).thenReturn(List.of(fromExec2));
        when(content.listByExecution("EXEC-2")).thenReturn(List.of(fromExec2));
        when(content.byId("CONT-1"))
                .thenReturn(java.util.Optional.of(fromExec1));
        when(content.listAll()).thenThrow(new AssertionError(
                "getContent must not read the whole table"));
        NeisMcpTools tools = tools();

        NeisMcpTools.ContentListResult byExec = (NeisMcpTools.ContentListResult)
                tools.getContent("EXEC-1", null, null, null);
        assertEquals(1, byExec.total());
        NeisMcpTools.ContentListResult byAddr = (NeisMcpTools.ContentListResult)
                tools.getContent(null, "ADDR-002", null, null);
        assertEquals(1, byAddr.total());
        NeisMcpTools.ContentListResult byId = (NeisMcpTools.ContentListResult)
                tools.getContent(null, null, "CONT-1", null);
        assertEquals("CONT-1", byId.records().get(0).id());
        assertThrows(NoSuchElementException.class,
                () -> tools.getContent(null, null, "CONT-nope", null));
    }

    @Test
    void theExportModeSubmitsTheJobAndPointsAtJobStatus() {
        // US-18.8 (issue #126): mode=export submits the export job —
        // the download URL arrives via neis_job_status, never inline
        // bytes in the tool result.
        RemoteJob job = new RemoteJob("JOB-exp", "export-content",
                "ADDR-001", Map.of("address-id", "ADDR-001"),
                JobStatus.QUEUED, Instant.now(), null, null, null, null,
                null);
        when(jobs.submitExport("ADDR-001", null)).thenReturn(job);
        NeisMcpTools.ContentExportResult accepted =
                (NeisMcpTools.ContentExportResult) tools()
                        .getContent(null, "ADDR-001", null, "export");
        assertEquals("JOB-exp", accepted.jobId());
        assertEquals("export-content", accepted.operation());
        assertEquals("QUEUED", accepted.status());
        assertTrue(accepted.next().contains("neis_job_status"),
                "the result points at the job-status poll: "
                        + accepted.next());
    }

    /**
     * The no-AI-over-MCP rule (owner decision 2026-11-19), enforced by
     * the ArchUnit rule mcpAdapterMustNotReachTheAiIntegrationPoint —
     * this test documents the class-level guarantee: NeisMcpTools'
     * constructor takes only the eight non-AI use cases (verified by
     * reflection so an added AI parameter fails HERE, not just in the
     * architecture test's output).
     */
    @Test
    void noToolReachesTheAiIntegrationPoint() {
        for (var parameter : NeisMcpTools.class.getConstructors()[0]
                .getParameterTypes()) {
            assertTrue(!parameter.getSimpleName().contains("Ai"),
                    "the MCP tool class must not depend on an AI use case: "
                            + parameter.getName());
        }
    }
}
