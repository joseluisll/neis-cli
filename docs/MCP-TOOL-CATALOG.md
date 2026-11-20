# NEIS MCP tool catalog (US-18.5, issue #120)

The MCP server exposes EXACTLY the 10 owner-decided tools below
(catalog decision 2026-11-19, issue #119). The full operation surface
remains available over REST; MCP is the agent-facing subset.

**No AI functionality is reachable through any tool** (owner decision
2026-11-19): there is no `ai-ask`, no AI-assisted registration, no
suggestion tool — the MCP adapter takes only the non-AI use cases
(enforced by reflection and an ArchUnit rule).

Only `neis_fetch_news` is asynchronous: it submits a job and returns
the job id; `neis_job_status` carries the full result payload when
finished. Every other tool completes within the call.

The schemas below are generated from the LIVE `tools/list` response
(installed server); the REST OpenAPI spec (`/v3/api-docs`, Swagger UI
at `/swagger-ui/index.html`) is generated from the controllers the
same way — examples come from real output, not invented.

## `neis_fetch_news`

Fetch news/events by running the configured fetch chain over ONE address (address_id) or over ALL registered addresses (omit address_id). ASYNCHRONOUS: returns the job id immediately — poll neis_job_status until finished; the result carries one outcome line per address.

- MCP hints: `destructiveHint`=false, `idempotentHint`=false, `readOnlyHint`=false
- REST counterpart(s): - REST counterpart(s): none direct — the MCP-only fetch wrapper over the US-18.2 job model; the REST equivalents are `POST /api/executions/run` (single task) and `POST /api/chains/{id}/run` (the fetch chain), polled via `GET /api/jobs/{id}`
- Parameters:
  - `addressId` (optional): string — optional: one address id; omit to fetch ALL registered addresses
  - `connector` (optional): string — optional: fetch strategy for this run; 'cached' serves the address's latest stored scrape (US-06.14) without touching the service's global connector; omit for the configured default

## `neis_get_content`

Content artifacts of the runs: list an execution's or an address's artifacts and read one (mode=read, default); with mode=export (+ execution_id or address_id) submit a content-export job instead — the download URL arrives via neis_job_status.

- MCP hints: `destructiveHint`=false, `idempotentHint`=false, `readOnlyHint`=false
- REST counterpart(s): GET /api/content · GET /api/content/{id} · GET /api/content/tree · POST /api/content/export · GET /api/exports/{token}
- Parameters:
  - `executionId` (optional): string — filter: one execution id
  - `addressId` (optional): string — filter: one address id
  - `contentId` (optional): string — one content record id
  - `mode` (optional): string — 'read' (default) or 'export'

## `neis_get_news_item`

One stored item in detail: the item record plus its sighting history (every capture in which the logical record appeared), merged.

- MCP hints: `destructiveHint`=false, `idempotentHint`=true, `readOnlyHint`=true
- REST counterpart(s): - REST counterpart(s): `GET /api/news/sightings/{canonicalId}` (the appearance history; the merged item-detail view is MCP-only)
- Parameters:
  - `canonicalId` **required**: string — the item's canonical id (canonicalId field of a neis_list_news item)

## `neis_job_status`

Status of a submitted job; carries the FULL result payload when finished (for neis_fetch_news: one outcome line per address).

- MCP hints: `destructiveHint`=false, `idempotentHint`=true, `readOnlyHint`=true
- REST counterpart(s): - REST counterpart(s): `GET /api/jobs` · `GET /api/jobs/{id}` · cancel via `POST /api/jobs/{id}/cancel`
- Parameters:
  - `jobId` **required**: string — the job id (JOB-...)

## `neis_list_addresses`

List registered addresses (id, kind, verification and webpage-type status); with address_id, return that one address in detail.

- MCP hints: `destructiveHint`=false, `idempotentHint`=true, `readOnlyHint`=true
- REST counterpart(s): GET /api/addresses · GET /api/addresses/{id}
- Parameters:
  - `addressId` (optional): string — optional: one address id for detail

## `neis_list_news`

Query the stored news/events repository: filter by address, kind (news/event), publication-date range and free-text terms (title/description/location).

- MCP hints: `destructiveHint`=false, `idempotentHint`=true, `readOnlyHint`=true
- REST counterpart(s): GET /api/news
- Parameters:
  - `addressId` (optional): string — filter: one address id
  - `kind` (optional): string — filter: 'news' or 'event'
  - `dateFrom` (optional): string — filter: range start (ISO or the extractor's date form)
  - `dateTo` (optional): string — filter: range end
  - `query` (optional): string — free-text terms over title, description and location
  - `limit` (optional): integer — max items (default 20)

## `neis_remove_address`

Remove a registered address. DESTRUCTIVE: the address and its selector/configuration state go away.

- MCP hints: `destructiveHint`=true, `idempotentHint`=false, `readOnlyHint`=false
- REST counterpart(s): DELETE /api/addresses/{id}
- Parameters:
  - `addressId` **required**: string — the address id to remove

## `neis_system_info`

NEIS entry point: version, scrape connector mode, known task types, address counts (registered/verified) and execution counts. Call this first to orient.

- MCP hints: `destructiveHint`=false, `idempotentHint`=true, `readOnlyHint`=true
- REST counterpart(s): GET /api/system
- Parameters: none

## `neis_upsert_address`

Register a new address or update an existing one (without address_id: register; null fields on update keep the current value).

- MCP hints: `destructiveHint`=false, `idempotentHint`=true, `readOnlyHint`=false
- REST counterpart(s): POST /api/addresses · PUT /api/addresses/{id}
- Parameters:
  - `addressId` (optional): string — existing address id to update (omit to register a new address)
  - `name` (optional): string — display name
  - `url` **required**: string — the page URL
  - `type` (optional): string — address type, e.g. URL_NEWS, URL_EVENTS, RSS_NEWS
  - `description` (optional): string — what the page is

## `neis_verify_address`

Run the verification probe for one address synchronously (bounded by the connector timeout); returns the eligibility status.

- MCP hints: `destructiveHint`=false, `idempotentHint`=true, `readOnlyHint`=false
- REST counterpart(s): POST /api/addresses/{id}/verify
- Parameters:
  - `addressId` **required**: string — the address id to verify

