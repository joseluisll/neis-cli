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
import es.jllopez.neis.domain.model.NewsEventItem;
import es.jllopez.neis.domain.model.NewsEventQuery;
import es.jllopez.neis.domain.model.NewsEventSighting;
import es.jllopez.neis.domain.model.RemoteJob;
import es.jllopez.neis.domain.model.VerificationOutcome;
import es.jllopez.neis.infrastructure.adapter.ConditionalOnServerEnabled;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * The MCP driving adapter (US-18.3/#118 + US-18.4/#119): a CURATED,
 * USER-ORIENTED tool set — workflow-level granularity, EXACTLY 10
 * tools, NO AI functionality (owner decisions 2026-11-19; the full
 * operation surface stays on REST, #116). Every tool delegates to the
 * SAME application use cases as the CLI/REST adapters — no logic
 * duplication across driving adapters — and returns TYPED records so
 * clients get structured output schemas (US-18.3 review finding).
 *
 * <p>Registered through Spring AI's {@code @McpTool} annotation
 * scanner (only in server mode; the scanner and the MCP
 * auto-configuration itself are gated by
 * {@code neis.server.enabled}). The catalog page (#120) documents
 * each tool against its REST counterpart(s).</p>
 */
@Component
@ConditionalOnServerEnabled
public class NeisMcpTools {

    private final SystemInfoService system;
    private final ManagementInfoService management;
    private final SettingsService settings;
    private final AddressCatalogService catalog;
    private final AddressVerificationService verification;
    private final RemoteJobService jobs;
    private final NewsEventService news;
    private final ContentStoreService content;

    public NeisMcpTools(SystemInfoService system,
                        ManagementInfoService management,
                        SettingsService settings,
                        AddressCatalogService catalog,
                        AddressVerificationService verification,
                        RemoteJobService jobs,
                        NewsEventService news,
                        ContentStoreService content) {
        this.system = system;
        this.management = management;
        this.settings = settings;
        this.catalog = catalog;
        this.verification = verification;
        this.jobs = jobs;
        this.news = news;
        this.content = content;
    }

    // ---- typed tool results (structured output schemas) ----------------

    /** Address as the agent sees it (the domain record's agent fields). */
    public record AddressView(String id, String name, String description,
                              String url, String type,
                              String verificationStatus, String verifiedAt,
                              String webpageType, boolean verified) {
        static AddressView of(Address a) {
            return new AddressView(a.id(), a.name(), a.description(),
                    a.url(), a.type().name(),
                    a.verificationStatus() == null ? null
                            : a.verificationStatus().name(),
                    a.verifiedAt(), a.webpageType(),
                    /* US-04.3: VERIFIED means eligible for runs */
                    a.verificationStatus() == es.jllopez.neis.domain.model
                            .VerificationStatus.VERIFIED);
        }
    }

    public record AddressListResult(List<AddressView> addresses,
                                    int total) {
    }

    public record FetchAccepted(String jobId, String operation,
                                List<String> addresses) {
    }

    public record NewsListResult(List<NewsEventItem> items, int total) {
    }

    public record NewsItemDetail(NewsEventItem item,
                                 List<NewsEventSighting> sightings) {
    }

    public record ContentListResult(List<ContentRecord> records,
                                    int total) {
    }

    // ---- tool 1: the entry point ---------------------------------------

    /**
     * Tool 1/10 — the agent's entry point. REST counterparts:
     * {@code GET /api/system} + {@code GET /api/dashboard}
     * (+ the scrape connector from the config allowlist).
     */
    @McpTool(name = "neis_system_info",
            description = "NEIS entry point: version, scrape connector mode, "
                    + "known task types, address counts (registered/verified) "
                    + "and execution counts. Call this first to orient.",
            annotations = @McpTool.McpAnnotations(
                    readOnlyHint = true, destructiveHint = false,
                    idempotentHint = true))
    public Map<String, Object> systemInfo() {
        es.jllopez.neis.domain.model.SystemInfo info = system.getSystemInfo();
        ManagementInfoService.Dashboard dash = management.dashboard();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", info.name());
        out.put("description", info.description());
        out.put("version", info.version());
        out.put("scrapeConnector", settings.effective("neis.scrape.connector"));
        out.put("taskTypes", dash.taskTypes());
        out.put("registeredAddresses", dash.addresses());
        out.put("verifiedAddresses", dash.verifiedAddresses());
        out.put("chains", dash.chains());
        out.put("executions", dash.executions());
        return out;
    }

    // ---- tool 2: address catalog ---------------------------------------

    /**
     * Tool 2/10. REST counterparts: {@code GET /api/addresses},
     * {@code GET /api/addresses/{id}}.
     */
    @McpTool(name = "neis_list_addresses",
            description = "List registered addresses (id, kind, verification "
                    + "and webpage-type status); with address_id, return that "
                    + "one address in detail.",
            annotations = @McpTool.McpAnnotations(
                    readOnlyHint = true, destructiveHint = false,
                    idempotentHint = true))
    public AddressListResult listAddresses(
            @McpToolParam(description = "optional: one address id for detail",
                    required = false) String addressId) {
        List<AddressView> addresses =
                (addressId == null || addressId.isBlank())
                        ? catalog.list().stream().map(AddressView::of).toList()
                        : List.of(AddressView.of(catalog.byId(addressId)));
        return new AddressListResult(addresses, addresses.size());
    }

    // ---- tool 3: register or update ------------------------------------

    /**
     * Tool 3/10. REST counterparts: {@code POST /api/addresses},
     * {@code PUT /api/addresses/{id}}.
     */
    @McpTool(name = "neis_upsert_address",
            description = "Register a new address or update an existing one "
                    + "(without address_id: register; null fields on update "
                    + "keep the current value).",
            annotations = @McpTool.McpAnnotations(
                    destructiveHint = false, idempotentHint = true))
    public AddressView upsertAddress(
            @McpToolParam(description = "existing address id to update "
                    + "(omit to register a new address)", required = false)
            String addressId,
            @McpToolParam(description = "display name", required = false)
            String name,
            @McpToolParam(description = "the page URL") String url,
            @McpToolParam(description = "address type, e.g. URL_NEWS, "
                    + "URL_EVENTS, RSS_NEWS", required = false) String type,
            @McpToolParam(description = "what the page is", required = false)
            String description) {
        AddressType parsed = parseType(type);
        Address saved = addressId == null || addressId.isBlank()
                ? catalog.create(null, name, description, url, parsed)
                : catalog.update(addressId, name, description, url, parsed);
        return AddressView.of(saved);
    }

    // ---- tool 4: remove (destructive) ----------------------------------

    /**
     * Tool 4/10. REST counterpart: {@code DELETE /api/addresses/{id}}.
     */
    @McpTool(name = "neis_remove_address",
            description = "Remove a registered address. DESTRUCTIVE: the "
                    + "address and its selector/configuration state go away.",
            annotations = @McpTool.McpAnnotations(destructiveHint = true))
    public AddressView removeAddress(
            @McpToolParam(description = "the address id to remove")
            String addressId) {
        return AddressView.of(catalog.remove(addressId));
    }

    // ---- tool 5: verification ------------------------------------------

    /**
     * Tool 5/10. REST counterpart: {@code POST /api/addresses/{id}/verify}.
     */
    @McpTool(name = "neis_verify_address",
            description = "Run the verification probe for one address "
                    + "synchronously (bounded by the connector timeout); "
                    + "returns the eligibility status.",
            annotations = @McpTool.McpAnnotations(
                    destructiveHint = false, idempotentHint = true))
    public VerificationOutcome verifyAddress(
            @McpToolParam(description = "the address id to verify")
            String addressId) {
        return verification.verify(addressId);
    }

    // ---- tool 6: THE core tool — fetch ---------------------------------

    /**
     * Tool 6/10 — ASYNCHRONOUS: returns the job id immediately; poll
     * with neis_job_status. REST counterpart: the run submissions
     * ({@code POST /api/chains/{id}/run}; fetch-all has no REST twin —
     * it is an MCP-level workflow over all addresses).
     */
    @McpTool(name = "neis_fetch_news",
            description = "Fetch news/events by running the configured fetch "
                    + "chain over ONE address (address_id) or over ALL "
                    + "registered addresses (omit address_id). ASYNCHRONOUS: "
                    + "returns the job id immediately — poll neis_job_status "
                    + "until finished; the result carries one outcome line "
                    + "per address.",
            annotations = @McpTool.McpAnnotations(
                    destructiveHint = false, idempotentHint = false))
    public FetchAccepted fetchNews(
            @McpToolParam(description = "optional: one address id; omit to "
                    + "fetch ALL registered addresses", required = false)
            String addressId) {
        RemoteJob job = jobs.submitFetchNews(
                addressId == null || addressId.isBlank() ? null : addressId);
        String joined = job.parameters().get("addresses");
        List<String> targets = joined == null || joined.isBlank()
                ? List.of()
                : List.of(joined.split(","));
        return new FetchAccepted(job.id(), job.operation(), targets);
    }

    // ---- tool 7: job polling -------------------------------------------

    /**
     * Tool 7/10. REST counterpart: {@code GET /api/jobs/{id}}.
     */
    @McpTool(name = "neis_job_status",
            description = "Status of a submitted job; carries the FULL result "
                    + "payload when finished (for neis_fetch_news: one "
                    + "outcome line per address).",
            annotations = @McpTool.McpAnnotations(
                    readOnlyHint = true, destructiveHint = false,
                    idempotentHint = true))
    public RemoteJob jobStatus(
            @McpToolParam(description = "the job id (JOB-...)") String jobId) {
        return jobs.job(jobId).orElseThrow(() ->
                new NoSuchElementException("Unknown job: " + jobId));
    }

    // ---- tool 8: the repository ----------------------------------------

    /**
     * Tool 8/10. REST counterpart: {@code GET /api/news}.
     */
    @McpTool(name = "neis_list_news",
            description = "Query the stored news/events repository: filter by "
                    + "address, kind (news/event), publication-date range and "
                    + "free-text terms (title/description/location).",
            annotations = @McpTool.McpAnnotations(
                    readOnlyHint = true, destructiveHint = false,
                    idempotentHint = true))
    public NewsListResult listNews(
            @McpToolParam(description = "filter: one address id",
                    required = false) String addressId,
            @McpToolParam(description = "filter: 'news' or 'event'",
                    required = false) String kind,
            @McpToolParam(description = "filter: range start (ISO or the "
                    + "extractor's date form)", required = false)
            String dateFrom,
            @McpToolParam(description = "filter: range end", required = false)
            String dateTo,
            @McpToolParam(description = "free-text terms over title, "
                    + "description and location", required = false)
            String query,
            @McpToolParam(description = "max items (default 20)",
                    required = false) Integer limit) {
        NewsEventQuery filters = new NewsEventQuery(
                blankToNull(addressId), blankToNull(kind),
                blankToNull(dateFrom), blankToNull(dateTo),
                limit == null ? NewsEventQuery.DEFAULT_LIMIT : limit);
        List<NewsEventItem> items = blankToNull(query) == null
                ? news.search(filters)
                : news.search(query, filters);
        return new NewsListResult(items, items.size());
    }

    // ---- tool 9: one item in detail ------------------------------------

    /**
     * Tool 9/10. REST counterparts: {@code GET /api/news/sightings/{id}}
     * (+ the repository's item rows).
     */
    @McpTool(name = "neis_get_news_item",
            description = "One stored item in detail: the item record plus its "
                    + "sighting history (every capture in which the logical "
                    + "record appeared), merged.",
            annotations = @McpTool.McpAnnotations(
                    readOnlyHint = true, destructiveHint = false,
                    idempotentHint = true))
    public NewsItemDetail getNewsItem(
            @McpToolParam(description = "the item's canonical id (canonicalId "
                    + "field of a neis_list_news item)") String canonicalId) {
        NewsEventItem item = news.item(canonicalId).orElseThrow(() ->
                new NoSuchElementException(
                        "Unknown item (canonical id): " + canonicalId));
        return new NewsItemDetail(item, news.sightings(canonicalId));
    }

    // ---- tool 10: content artifacts ------------------------------------

    /**
     * Tool 10/10. REST counterparts: {@code GET /api/content},
     * {@code GET /api/content/{id}} (+ the export endpoint from
     * US-18.8 in export mode).
     */
    @McpTool(name = "neis_get_content",
            description = "Content artifacts of the runs: list an execution's "
                    + "or an address's artifacts and read one (mode=read, "
                    + "default); with mode=export (+ execution_id or "
                    + "address_id) submit a content-export job instead — the "
                    + "download URL arrives via neis_job_status.",
            annotations = @McpTool.McpAnnotations(
                    readOnlyHint = true, destructiveHint = false))
    public ContentListResult getContent(
            @McpToolParam(description = "filter: one execution id",
                    required = false) String executionId,
            @McpToolParam(description = "filter: one address id",
                    required = false) String addressId,
            @McpToolParam(description = "one content record id",
                    required = false) String contentId,
            @McpToolParam(description = "'read' (default) or 'export'",
                    required = false) String mode) {
        if (mode != null && "export".equalsIgnoreCase(mode.trim())) {
            return exportContent(executionId, addressId);
        }
        List<ContentRecord> records;
        if (contentId != null && !contentId.isBlank()) {
            records = List.of(content.byId(contentId).orElseThrow(() ->
                    new NoSuchElementException(
                            "Unknown content record: " + contentId)));
        } else {
            // use-case queries, not an unbounded listAll (US-18.4
            // review): the address path matches the REST twin; when
            // both filters arrive, the smaller execution set narrows
            boolean byExec = executionId != null && !executionId.isBlank();
            boolean byAddr = addressId != null && !addressId.isBlank();
            records = byExec ? content.listByExecution(executionId)
                    : byAddr ? content.listByAddress(addressId)
                    : content.listAll();
            if (byExec && byAddr) {
                records = records.stream()
                        .filter(r -> addressId.equals(r.addressId()))
                        .toList();
            }
        }
        return new ContentListResult(records, records.size());
    }

    /**
     * The export path lands with US-18.8 (issue #126) — the ZIP export
     * use case (US-11.5) does not exist yet; refuse visibly instead of
     * pretending. (This is the catalog's only placeholder; the tool
     * count stays at EXACTLY 10.)
     */
    private ContentListResult exportContent(String executionId,
                                            String addressId) {
        throw new IllegalStateException(
                "Content export over the remote adapters lands with "
                        + "US-18.8 (issue #126) — use the CLI's export for now");
    }

    // ---- helpers --------------------------------------------------------

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static AddressType parseType(String type) {
        if (blankToNull(type) == null) {
            return null;
        }
        try {
            return AddressType.valueOf(type.trim().toUpperCase());
        } catch (IllegalArgumentException unknown) {
            throw new IllegalArgumentException("Unknown address type: "
                    + type + " (URL_NEWS, URL_EVENTS, RSS_NEWS, ...)");
        }
    }
}
