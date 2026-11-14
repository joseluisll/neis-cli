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
| `ContentScrapePort` | driven | `ScrapeConnectorRouter` → `StaticScrapeConnector` (default: HTML5 fixtures of issue #62, offline runs) or `HttpScrapeConnector` (`neis.scrape.connector=live`, US-06.1: java.net.http fetch + jsoup HTML5 normalization) |
| `SimplifyPort` | driven | `JsoupSimplifyConnector` (jsoup cleansing, 100 KB cap) |
| `SelectorExtractionPort` | driven | `JsoupSelectorConnector` (jsoup selector-based item extraction) |
| `AddressCatalogPort` | driven | `AddressCatalogConnector` (US-03: the registered address catalog over the SQLite `addresses` table) |
| `AddressVerificationPort` | driven | `HttpClientAddressVerificationConnector` (US-04.2: HEAD with GET fallback, redirects, configurable timeout) |
| `ExecutionStorePort` | driven | `ExecutionStoreConnector` (US-05.3: execution records + the task-type vocabulary over the SQLite `executions`/`task_types` tables) |
| `ChainStorePort` | driven | `ChainStoreConnector` (US-08.1: chain definitions over the SQLite `chains`/`chain_tasks` tables) |
| `ContentStorePort` | driven | `ContentStoreConnector` (US-11: webpage captures + content records over the SQLite `captures`/`content_records` tables) |
| `SelectorStorePort` | driven | `FileSelectorStoreConnector` (per-address validated selectors as JSON under `<work dir>/selectors`) |

The SSAFP pipeline (WEBSCRAPE - SIMPLIFY - ANALYZE - FILTER - PRINT,
ex the MVP chain, issue #90 + US-17) lives in `PipelineService` behind
the executor's task types: the task types are the pipeline's stages as
runnable prefixes, and the configured chain `core-chain-ssafp`
(`core/run.sh` creates and runs it) executes them in order over an
address — the hardcoded MVP orchestration (`MvpChainService`,
`MvpChainResult`, the `mvp-chain` command and the `mvp/` run kit) was
retired when the Core Platform took over. The kit's PRINT step
persists a readable report artifact per address in the content tree.
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
  the catalog.
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
