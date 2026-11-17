# NEIS Architecture

NEIS is a CLI application organized as a hexagonal architecture
(ports and adapters). Dependencies point inwards only:

```
                    ┌─────────────────────────────────────────┐
                    │             infrastructure              │
                    │  ┌─────────┐  ┌─────────┐  ┌─────────┐  │
   CLI in ────────▶ │  │ shell   │  │ system  │  │   ai    │  │
                    │  │ adapter │  │ adapter │  │ adapter │  │
                    │  └────┬────┘  └────┬────┘  └────┬────┘  │
                    └───────┼────────────┼────────────┼───────┘
                            ▼            ▼            ▼
                    ┌─────────────────────────────────────────┐
                    │              application                │
                    │        (use cases, orchestration)       │
                    └───────────────────────┬─────────────────┘
                                            ▼
                    ┌─────────────────────────────────────────┐
                    │                 domain                  │
                    │     (model + ports, pure Java)          │
                    └─────────────────────────────────────────┘
```

## Layers

- **domain** (`es.jllopez.neis.domain`): the heart. Pure Java — no Spring,
  no third-party libraries. Contains the model and the **ports**:
  interfaces the domain owns and the outside world must implement.
- **application** (`es.jllopez.neis.application`): use cases. Depends only
  on domain ports. May use Spring annotations for wiring (`@Service`),
  but no infrastructure.
- **infrastructure** (`es.jllopez.neis.infrastructure`): **connectors**
  (adapters). Driving adapters (e.g. Spring Shell commands) invoke
  application use cases; driven adapters implement domain ports
  (e.g. runtime info from build properties, AI via Spring AI, retrieval,
  persistence). This is the only layer allowed to touch third-party
  libraries and frameworks.

## Ports so far

| Port | Direction | Connector |
|---|---|---|
| `RuntimeInfoPort` | driven | `MavenRuntimeInfoConnector` (Spring Boot build properties) |
| `AiAssistantPort` | driven | `SpringAiConnector` (Spring AI `ChatClient`, provider-neutral) |
| `ContentScrapePort` | driven | `ScrapeConnectorRouter` → `StaticScrapeConnector` (default: HTML5 fixtures of issue #62, offline runs), or — `neis.scrape.connector=live` — PER-TYPE retrieval (US-06.5, issue #71): the address's classified webpage type selects `HttpScrapeConnector` (US-06.1/06.2: java.net.http fetch, Tika content-type/charset handling, jsoup HTML5 normalization, body cap + fetch deadline) for `http` types or `PlaywrightRenderConnector` (browser rendering, lazy: driver + Chromium provision on FIRST USE) for the `rendered` types (spa-*, hybrid); the WEBSCRAPE `connector` parameter overrides per run (`fixtures`/`http`/`rendered`). Filters (US-06.6, issue #73): the WEBSCRAPE filter set — per-run `filter.<name>=<value>` parameters, or the address's preconfigured set (`neis.filters.preconfigured`) — is applied BEFORE extraction: URL query parameters for static pages, operated filter controls (select/text/checkbox by name/id/label, submit + re-settle) for rendered pages, captured filtered responses for the fixtures; the applied set is recorded on the capture metadata (`captures.filters`, V11) and a filter set the source cannot serve is refused loud — never silently unfiltered content |
| `FeedSourcePort` | driven | `RomeFeedConnector` (US-06.8, issue #86: a feed-classified webpage type routes WEBSCRAPE to the feed path — the RSS/Atom document is fetched with the HTTP scrape discipline (timeout, size cap, watchdog) and parsed with Rome into standard news/event records; no HTML scraping when the feed suffices; same repository persistence, provenance and `max-items` cap as the scrape path; a selector override is refused loud) |
| `SimplifyPort` | driven | `ReadabilitySimplifyRouter` (US-07.5, issue #87: `@Primary` — the router behind the port, US-01.4 replaceability) routing per page structure: ARTICLE-LIKE pages (semantic article marker — `<article>`, `[role=article]`, schema.org NewsArticle/BlogPosting/Article itemtype or JSON-LD — AND prose dominance: paragraph text at least half the page's text, size floor 400 chars) go through **Readability4J** (Apache 2.0 Java port of Mozilla Readability; its jsoup 1.11.2 POM declaration is probe-verified compatible with the pinned jsoup 1.24.1) FIRST — the extracted article region (title re-attached when Readability drops it) is then handed through the same jsoup cleanse, so the output contract below holds on both paths; a Readability null/failed extraction falls back to the jsoup rules, never a failed run; NON-ARTICLE structures (listings, tables, filter grids — the probe rejects them) go straight to the jsoup Safelist rules without data loss. The jsoup path itself is `JsoupSimplifyConnector` (jsoup cleansing, 100 KB cap; US-07.4: every produced output is re-parsed with error tracking and scanned for forbidden non-data elements (scripts, styles, media, popups, embedded objects — event handlers stripped) and a failed check is a task-run error; the SIMPLIFY artifact write sites (pipeline page-1 + pagination, FILTER-applied response, the SIMPLIFY/ANALYZE/FILTER task paths) all go through the guarded write). US-07.6 (issue #89): the port gained the tiered `simplify(html, variant)` — `full` (default; the routing above), `economy` and `lean`, rendered FROM THE PRUNED ELEMENT TREE (the `JsoupDomAnalyzer.prune` DOM transform, the US-06.9 walk semantics) by `PrunedTreeHtmlRenderer` (Skyvern's json_to_html concept re-implemented in Java, NO code copied): economy truncates the longest texts then drops textless presentational containers toward a 60%-of-full target; lean strips query strings KEEPING the pagination params (US-17 page data), collapses non-navigable links to their text and hashes oversized URLs into `hash:<12hex>` placeholders with the mapping stored on the capture (`captures.simplify_side_table`, V17, never-wipes); the tier is a chain/task parameter (`variant=economy|lean`), icon-only controls keep their ARIA label as real text (pseudo-element preservation), and a null/blank variant normalizes to full BEFORE routing (the default tier must not bypass the Readability path) |
| `SelectorExtractionPort` | driven | `JsoupSelectorConnector` (jsoup selector-based item extraction, US-12.2: deterministic `Document.select` iteration — title, description, date, detail URL resolved against the address's base URI) |
| `AddressCatalogPort` | driven | `AddressCatalogConnector` (US-03: the registered address catalog over the SQLite `addresses` table) |
| `AddressVerificationPort` | driven | `HttpClientAddressVerificationConnector` (US-04.2: HEAD with GET fallback, redirects, configurable timeout; US-06.5: the probe also classifies the page into the webpage-type taxonomy — `WebpageTypeClassifier` marker heuristics over a bounded body sample, a null classification never wipes a stored type) |
| `ExecutionStorePort` | driven | `ExecutionStoreConnector` (US-05.3: execution records + the task-type vocabulary over the SQLite `executions`/`task_types` tables) |
| `ChainStorePort` | driven | `ChainStoreConnector` (US-08.1: chain definitions over the SQLite `chains`/`chain_tasks` tables) |
| `ContentStorePort` | driven | `ContentStoreConnector` (US-11: webpage captures + content records over the SQLite `captures`/`content_records` tables) |
| `NewsEventStorePort` | driven | `NewsEventStoreConnector` (US-14: the news/events repository over the SQLite `news_events` table — upsert on a deterministic id, US-14.2 provenance columns, US-14.3 filtered search) |
| `SelectorStorePort` | driven | `FileSelectorStoreConnector` (per-address validated selectors as JSON under `<work dir>/selectors`) |
| `ObstructionClearingPort` | driven | `JsoupObstructionClearer` (US-06.7, issue #75: popups, cookie/consent banners, modal/dialog overlays and advertisement nodes removed from the captured HTML5 BEFORE storage or downstream tasks — generic conservative rules (declared overlays, consent vocabulary, ad containers) plus the page-specific configured selectors (`neis.scrape.clearing.selectors`, US-12 model, US-12.3-validated at boot), all under a main-content guard that never removes a wrapper carrying `<main>`/`[role=main]`/multiple articles/filter forms; the rendered connector additionally DISMISSES popups and clicks consent/close controls in the browser before serializing; the removal record (`{"removed":N,"rules":[...]}`) lands on the capture metadata (`captures.cleared`, V15) under the never-wipes rule) |
| `DomAnalysisPort` | driven | `JsoupDomAnalyzer` (US-06.9, issue #88: the captured HTML5 is walked into the pruned element tree — Skyvern concepts re-implemented in Java, NO Skyvern code copied (AGPL respected): incremental element ids (`e1`, `e2`, ...) in document order with a deterministic id-to-CSS map (every path resolves uniquely on a re-parse), visibility/relevance filtering with Skyvern's isDropped semantics (dropped non-interactable elements disappear from the tree, dropped interactable ones keep their tag but lose their attributes), interactability detection (links/buttons/controls with `disabled`/`aria-disabled` state; valueless boolean attributes recorded as `"true"`), an attribute whitelist with value trimming (200-char cap) and long-URL hashing into a placeholder map (`hash:<12hex>`, mapped back in the catalog), and a SHA-256 change digest over the canonical tree (text included — a content change changes the digest); the catalog is stored per capture as the `element-catalog.json` artifact (the AI's numbered-tree view for US-13.3/US-13.4) and the digest lands on the capture metadata (`captures.dom_digest`, V16) under the never-wipes rule) |
| `SettingsStorePort` | driven | `SettingsStoreConnector` (V13 `settings` table, US-16.5 edit path from #97: persisted configuration overrides, validated against the key's allowlist rule BEFORE saving — `SettingsService` — and layered over application.yml; `SettingsOverrideApplier` applies every stored override at boot and pushes an edit into the live consumers that read at use time — scrape-connector mode, extraction cap, verification timeout; the AI provider and boot-critical paths stay read-only, edited in application.yml) |

The SSAFP pipeline (WEBSCRAPE - SIMPLIFY - ANALYZE - FILTER - PRINT,
ex the MVP chain, issue #90 + US-17) lives in `PipelineService` behind
the executor's task types: the task types are the pipeline's stages as
runnable prefixes, and the configured chain `core-chain-ssafp`
(`core/run.sh` creates and runs it) executes them in order over an
address — the hardcoded MVP orchestration (`MvpChainService`,
`MvpChainResult`, the `mvp-chain` command and the `mvp/` run kit) was
retired when the Core Platform took over. The kit's PRINT step
persists a readable report artifact per address in the content tree.
The ANALYZE selector strategy (US-12.1/#46): stored fast path first,
then the configuration's preconfigured selectors
(`neis.selectors.preconfigured`), then the AI, then static heuristics —
a validated preconfigured selector is persisted with
`discoveredBy=preconfigured`.
Pure-Java domain services (`PaginationDetector`,
`ExposedFilterDetector`) are the heuristic fallbacks when no AI is
configured; AI classification, selector identification, capability
detection (pagination + page URL pattern, filtering) and filter
extraction all reuse `AiAssistantPort` through the task-specific prompts
of `AiPromptCatalog` — no site-specific knowledge is encoded in code.

## Capability flagging (US-17)

ANALYZE detects the listing capabilities of an address — pagination and
filtering — and FLAGS them onto the address (`AddressCapability`,
`Address.hasCapability`). Downstream blocks execute their
page-dependent functionality only on flagged addresses:

- the pagination-iteration block scrapes following pages (via the
  page-aware `ContentScrapePort.scrape(address, pageIndex)`) only on
  addresses flagged PAGINATION, following the AI-derived page URL
  pattern up to the item cap;
- the FILTER block extracts the filter definitions
  (`FilterDefinition`) only on addresses flagged FILTERING, and then
  APPLIES one criterion before extraction (US-06.6 slice, issue #73):
  the AI chooses one criterion and derives the filter request
  (constrained to what the SCRAPE connector reports it can apply via
  `ContentScrapePort.supportedFilterRequests`), the connector serves the
  filtered response (`ContentScrapePort.scrapeFiltered` — captured
  real filtered pages in the static MVP), and the same validated
  selector extracts the narrowed listing, reported next to the
  unfiltered one. The static connector's supported-request list
  (`StaticScrapeConnector.FILTER_CRITERIA`) is fixture metadata — which
  criterion each captured response embodies, the same class of static
  stand-in as its fixture registry; a production connector derives the
  list from its real scraping/rendering capability. Addresses without
  filter controls are reported skipped; there is no client-side/semantic
  filtering of extracted items (owner decision 2026-11-11).

SIMPLIFY keeps action/data elements that capability detection needs:
pagination navs (pager chrome) and filter forms with selects survive
the cleanse; duplicated multi-select widget clones (checkbox copies of
select options) are dropped so the real data stays inside the 100 KB
cap.

## Filter catalogue and chain filter specs (owner decision 2026-11-12)

- The FILTER step's identification is persisted with the address: the
  filter-zone selector AND the filter catalogue (every extracted field
  with ALL its possible values) live in the same selector-store entry.
  Whatever valid catalogue is extracted — by the AI or the generic
  HTML5 parser — is stored merged, so later runs (and no-AI runs)
  reuse it; the generic parser names fields by their VISIBLE labels so
  AI-named and parser-named catalogues agree.
- A chain can CARRY a filter criterion (data, not code):
  `chain create ... --filter "field=<name>;value=<value>"` or
  `--filter-spec <path.json>` ({"field":..., "value":...} or
  {"filters":[...]}); the resolved spec is stored as the FILTER step's
  parameters and belongs to the chain, not the address.
- At run time the executor threads the chain's spec to every step: the
  FILTER application applies THAT criterion offline — the spec is
  coherence-matched (lenient, case-insensitive) against the address's
  stored catalogue and the SCRAPE connector's servable requests
  (`FilterRequestOption`: criterion + request token); the matched
  request is applied before the response. No AI in the loop; the trace
  logs `filter application from the chain spec - AI bypassed`. A spec
  that matches nothing degrades to a skipped note, never a chain
  failure. Without a spec the AI still chooses the criterion (or, with
  no AI, application is skipped).

## Persistence (US-02)

- SQLite3 is the database of the first iteration (owner decision
  recorded in issue #15): `sqlite-jdbc` (Boot-managed) + Hibernate
  community `SQLiteDialect` on Spring Data JPA. The database file is
  externalized via `neis.db.path`.
- Schema is owned by Flyway migrations (`db/migration`, `Vn__*.sql`),
  never by Hibernate (`ddl-auto: none`). An applied migration is never
  edited; schema changes ship as new `Vn` files (US-02.3).
- All DB access stays behind repository ports; JPA entities and Spring
  Data repositories live in `infrastructure.persistence` and never leak
  into domain or application (enforced by the stack-freeze ArchUnit
  rule, which blocks JPA/Hibernate/Spring Data/sqlite there).

## Addresses: catalog, validation, eligibility (US-03, US-04)

- The registered address catalog is database-backed
  (`AddressCatalogPort` -> `AddressCatalogConnector`); it ships EMPTY —
  there is no automatic seeding (owner decision 2026-11-11). CRUD runs
  through the `address add/list/show/edit/remove/verify` commands
  (US-03.2); catalog edits never touch the verification columns.
- Syntactic URL validation (US-04.1): `UrlSyntaxValidator` (pure
  domain service) demands an absolute http/https URL with a host; it
  runs at creation/edit time and again before an executor uses the
  address.
- Verification (US-04.2): `AddressVerificationPort` checks
  reachability (HEAD with GET fallback, redirects followed, timeout via
  `neis.address.verification.timeout-seconds`); the outcome
  (VERIFIED/FAILED + ISO checked-at) persists on the address through
  the catalog. A verified verification makes up to THREE HTTP requests
  (US-06.10 docs finding): (1) the HEAD probe that decides reachability;
  (2) a GET fallback, only when the server refuses HEAD (405/501);
  (3) the classification GET below, only after a verified 2xx — an
  unverified or failed probe makes no classification request.
- Webpage-type classification (US-06.5, issue #71): the verification
  probe also classifies the page into the EXTENSIBLE, configuration-
  driven taxonomy (`WebpageTypeCatalog`: built-ins `static`, `spa`,
  `spa-angular`, `spa-react`, `spa-vue`, `hybrid`, `other`; new types
  via `neis.webpage.types.<id>.strategy` — no domain change) using the
  deterministic `WebpageTypeClassifier` marker heuristics over a
  bounded body sample (framework markers → `spa-<family>`;
  markers + `<noscript>` fallback → `hybrid`; an RSS/Atom document —
  feed content type or `<rss>`/`<feed>` root element — → `feed`;
  non-HTML → `other`; plain HTML → `static`; a null classification
  never wipes a stored type). The type persists on the address and on
  every capture; `address webpage-type` sets/clears it explicitly, and the retrieval
  strategy follows from the type in `live` mode (per-run WEBSCRAPE
  `connector` parameter overrides). An unclassifiable probe NEVER
  erases a stored type. The classification GET is the probe's third
  request: a separate bounded fetch (256 KB sample cap, watchdog
  deadline) whose response must itself be 2xx — an error page is
  refused unclassified, never classified.
- Eligibility (US-04.3): executors accept only addresses that are
  syntactically valid AND VERIFIED. The executor refuses ineligible
  addresses; batch runs skip them and report the skip count.

## Execution: async executor, scheduling, records (US-05)

- Every task run — manual (US-05.4) or scheduled (US-05.2) — goes
  through `ExecutionService`, which validates the task type, refuses
  ineligible addresses (the US-04.3 gate), runs the work on a small
  background executor pool so the caller never blocks, and drives the
  execution record's lifecycle (PENDING -> RUNNING -> SUCCESS/FAILED).
  Lifecycle saves are failure-tolerant: a store failure never strands
  a record or loses an outcome. Runs over the SAME address serialize
  on a per-address lock — the work tree and selector store are not
  safe for concurrent runs.
- Task types are the chain's stages as runnable prefixes (WEBSCRAPE,
  SIMPLIFY, ANALYZE, FILTER, PRINT) plus the whole CHAIN
  (`TaskWorkService`); the fixed vocabulary is seeded idempotently into
  the `task_types` table (schema vocabulary, not user data).
- Execution records (US-05.3) live in the `executions` table behind
  `ExecutionStorePort`; `execution run --wait`, `execution list/show`
  expose them. The `executions.task_code` and `address_id` FKs are
  enforced.
- Schedules (US-05.2) are configuration, never code:
  `neis.schedule.entries` (name, task, address, Spring 6-field cron)
  registers cron triggers (`ScheduleRegistrar`) that submit SCHEDULED
  runs to the executor. A refused submission is logged and does not
  cancel the schedule.
- The executor's workers are DAEMON threads (ExecutorConfig): plain
  non-daemon pool workers block JVM exit after any async CLI run, and
  the Spring-managed `shutdown()` only runs during shutdown — a
  deadlock the US-08 chain-run verification reproduced.

- Timestamps in new entities are ISO-8601 TEXT at the entity boundary
  and typed `Instant` in the domain — SQLite stores TEXT (the US-02
  lesson; `Instant` columns fail Hibernate's timestamp parsing).

## Chains of tasks (US-08)

- A chain (US-08.1, `Chain`/`ChainTask`) is an ordered list of 1..10
  task elements; the order is DATA (explicit 1..n positions, no gaps),
  so the shape generalizes to a DAG later. A chain is not bound to an
  address — the execution is; steps cannot be CHAIN (no nesting) and
  must come from the `task_types` vocabulary (validated at creation,
  enforced again by the `chain_tasks` FK).
- Definitions persist in the schema V1 `chains`/`chain_tasks` tables
  behind `ChainStorePort` (`ChainStoreConnector`); saving replaces the
  task rows wholesale; per-task parameters (US-08.3) round-trip as the
  schema's `parameters_json`.
- `chain create/list/show/run` manage and execute definitions;
  `chain run` submits through `ExecutionService.submitChain`, so a
  chain run uses the SAME lifecycle, eligibility gate, per-address
  serialization and execution-record machinery as any task run — the
  record carries `task_code=CHAIN` plus the `chain_id` link.
- Sequential execution (US-08.2): steps run in position order inside
  the same per-address lock; each step gets its own parameters.
  PRINT/CHAIN steps accept `max-items` (1..10000, default 50) and
  `max-pages` (1..100, default 10); tasks that take no parameters yet
  REFUSE them (dead configuration is an error, not silence).
- Chain-level exception handling (US-08.4): a failing step aborts the
  chain — the record goes FAILED with the failed step, its cause and
  the completed steps; later steps never run. The outcome column is
  capped at 4000 chars.

## Content storage (US-11)

- Storage is a first-class capability: every task output lands in a
  deterministic filesystem tree mirroring the domain model (US-11.4):
  `<content root>/<address id>/<capture id>/content/<task code>/<file>`
  plus `metadata/capture.json` (the fetch record). One folder per
  registered address (US-11.1), one per webpage capture, content
  organized per task within the capture (US-11.2, US-11.3).
- Determinism: the capture id derives from the webpage URL (SHA-256
  prefix, never run timestamps) and a content record's id derives from
  address+capture+task+file — repeated operations land in (and
  update) the same place; there are no timestamped copies. History
  belongs to the repository (US-14), not to duplicated folders.
- Consistency: every folder is backed by an entity and vice versa —
  addresses (schema V1), captures (new `captures` table, V2
  migration, composite PK address+capture), content files
  (`content_records`, V1, UNIQUE path). All writes go through
  `ContentStoreService` behind `ContentStorePort`.
- The pipeline work dir IS the content root (`neis.core.work-dir`,
  externalized; the kit pins it via `NEIS_CORE_WORK_DIR`). The
  selector store stays at `<root>/selectors/` (unchanged this slice).
- `content list [--address]` and `content tree --address` inspect the
  stored records; the tree view is the filesystem projection of the
  database rows.

## News/events repository (US-14)

- Every extracted item is persisted as an entity (schema V3
  `news_events`, provenance columns V4) through `NewsEventService`
  behind `NewsEventStorePort`: upsert on a deterministic
  (address, detail URL — or title) id, so re-extraction relinks the
  same row instead of duplicating it. Each row carries its US-14.2
  provenance — the extracting execution and the scrape.html content
  record — refreshed on every upsert; `news_events` cannot carry FK
  columns on those fields (SQLite ALTER TABLE limitation), so
  `NewsEventService.provenanceGaps()` verifies the references instead
  (`news provenance` command).
- Querying (US-14.3): `news list [--address] [--kind] [--from] [--to]
  [--limit]` lists items newest first with their provenance; dates are
  parsed leniently (ISO and the extractor's human form) by
  `NewsEventQuery.dateInRange`. The same commands inside the
  interactive shell are the repository's TUI view.
- Address management TUI (US-09.1, issue #37): `address tui` opens an
  interactive screen over the address catalog (list, add, edit,
  remove) built on the shell's own JLine terminal — no new dependency.
  The screen redraws from live state after every operation and shows
  each address's validation/verification status (US-09.2, issue #38):
  the syntax-validation outcome of the STORED url (`AddressCatalogService
  .urlValidationProblem` — VALID, or INVALID with the
  `UrlSyntaxValidator` reason, re-evaluated per redraw, never a live
  probe) next to the stored verification state
  (UNVERIFIED/VERIFIED/FAILED + the ISO checked-at instant) and the
  classified webpage type. Empty input keeps the default (auto `ADDR-NNN`
  id, current value on edit); an invalid value aborts that operation
  loud without touching the catalog; a removal needs an explicit `y`;
  EOF (piped input, Ctrl-D) exits cleanly, so the screen is
  script-verifiable.

## Management UI (US-16)

- The unified management interface (US-16, epic #77) is one
  interactive TUI opened by the `manage` command
  (`ManagementCommands` -> `ManagementTui`, same JLine-terminal
  pattern as `address tui`, no new dependency). One main screen with
  the sections as numbered entries; every section redraws from live
  state after every operation, and EOF/Ctrl-C exit cleanly at every
  level (script-verifiable like the address screen).
- The dashboard (US-16.2, issue #79) IS the main screen's content:
  `ManagementInfoService` (application layer) aggregates catalog
  health (US-03/US-04 verification counts), chain definitions
  (US-08), execution records with the last run (US-05.3), the
  repository size (US-14) and the configured schedules (US-05.2) plus
  the AI availability (US-15.3) into one snapshot recomputed per
  redraw.
- Address management (US-16.1's US-09 part, issue #78): the section
  re-enters the existing `AddressTui` screen — one address
  management implementation, no duplicate logic.
- Execution management (US-16.3, issue #80): the section lists the
  records newest first, shows a run's detail (task, address, chain
  link, trigger, status, timestamps, outcome) with the produced
  content records linked to their US-11.4 storage-tree paths, and
  re-runs a record (its task, or its chain when the record carries
  `chainId`) — the re-run goes through the same `ExecutionService`
  submit path, so the eligibility gate (US-04.3) and idempotency
  (US-15.4) apply unchanged.
- Repository browsing (US-16.4, issue #81): news/events listed newest
  first with the incompleteness flag visible (US-14.4), an item
  detail with every metadata field and its US-14.2 provenance, and
  the content tree rendered from the content records
  (address / capture / task paths) with the content root shown.
- Configuration management (US-16.5, issue #82): the section shows
  the effective operational configuration read-only — content root,
  AI status, the schedule entries, and per-address stored selectors
  with the filter catalogue (US-12.1/12.6 visibility). The
  externalized configuration (US-15.1, application.yml) stays the
  source of record: the section validates what it shows is loadable
  (an unknown address or a load failure is reported loud) but does
  not edit configuration files; editable-through-the-TUI
  configuration is a later slice.

- Mandatory metadata (US-14.4, schema V5): title, description and date
  are the mandatory minimum (publication date for news, event date for
  events); picture and location are optional, captured when the listing
  shows them. The store evaluates completeness on EVERY save
  (`NewsEventItem.missingMandatory()`) — a record missing mandatory
  data is stored flagged `incomplete` with the missing fields recorded,
  never silently as complete, and a later upsert that fixes the data
  clears the flag. `news list` marks such rows
  `INCOMPLETE(missing: ...)` so selector or source problems are visible
  in the listing itself.
- Deduplication across captures (US-14.5, schema V6): every row carries
  a DETERMINISTIC `canonical_id` — hashed from the item's stable
  identifier (its detail URL, fragment/trailing-slash normalized) or,
  for link-less items, from the similarity baseline (normalized title +
  leniently parsed date, kind-agnostic) — so the same story seen again
  (later capture, paginated page, other address) lands on the same
  canonical id without any lookup, while each address keeps its own
  row. Every capture that produced an item also records a SIGHTING
  (`news_event_sightings`, one row per item × execution/content pair)
  with its US-14.2 provenance — re-encounters add sightings, never
  records. `news sightings [--address] [--limit]` shows the appearance
  history grouped by canonical id (one logical record, with the
  addresses it was seen on).
- Export (US-14.6): `news export --format json|csv [--address] [--kind]
  [--from] [--to] [--incomplete all|complete|incomplete] [--out <path>]`
  writes the matching records (default `./news-events.<ext>`,
  overwritten in place) through `NewsEventExportService`. Records carry
  the full metadata: US-14.4 mandatory fields, the completeness flag
  with the missing fields, the US-14.5 canonical id and the US-14.2
  provenance. Deterministic: records are sorted by natural identity
  (never write timestamps), JSON keeps a fixed field order and CSV is
  fully quoted (RFC 4180) — the same data and filters produce
  byte-identical files.
- Full-text search (US-14.7, schema V7-V9): `news search --query <terms>
  [--address] [--kind] [--from] [--to] [--limit]` matches the terms
  against title, description and location through an FTS5
  external-content index (`news_events_fts`) kept consistent with the
  repository by INSERT/UPDATE/DELETE triggers; results come ranked by
  relevance (FTS5 bm25 `rank`) with the US-14.3 filters combined on
  top. User terms are quoted into FTS5 phrases, so syntax injection
  cannot crash or redirect the match. The index is backfilled from the
  existing rows at migration time (V9 uses the documented 'rebuild'
  command — the V8 INSERT...SELECT variant was a no-op, because an
  external-content table answers `SELECT rowid` from its content
  table).

## Enforced rules (ArchUnit)

- `domain` may not depend on `application` or `infrastructure`.
- `application` may not depend on `infrastructure`.
- `domain` must be free of Spring imports.
- `domain` and `application` must not import the open-source building
  blocks listed in `docs/TECH-STACK.md` (jsoup, HttpClient 5, Playwright,
  Tika, Hibernate/JPA, Spring AI, Rome, Readability4J, sqlite) — these
  are implementation details behind the ports (issue #63).

## Conventions

- New functionality = a new port in `domain.port` (if the outside world
  must provide something) plus its connectors in
  `infrastructure.adapter`, wired through an application use case.
- CLI commands live in `infrastructure.adapter.shell` and stay thin.
