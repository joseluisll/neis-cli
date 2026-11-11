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
| `ContentScrapePort` | driven | `StaticScrapeConnector` (MVP: static HTML5 fixtures of issue #62; real fetching connectors plug in behind it) |
| `SimplifyPort` | driven | `JsoupSimplifyConnector` (jsoup cleansing, 100 KB cap) |
| `SelectorExtractionPort` | driven | `JsoupSelectorConnector` (jsoup selector-based item extraction) |

The MVP static chain (SCRAPE - SIMPLIFY - ANALYZE - FILTER - PRINT,
issue #90; FILTER added for US-17) is orchestrated by `MvpChainService`
over these ports. Pure-Java domain services (`PaginationDetector`,
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
