# NEIS Operator Manual

News and Events Information System — operating the `neis-cli` application:
registering sources, running tasks and chains, and working the news/events
repository. This manual documents the delivered system (all requirements
implemented); the internal design lives in `ARCHITECTURE.md` and the
`neis-*.html` diagrams.

---

## 1. What NEIS does

NEIS watches a catalog of web pages that list news and events (EU agency
sites, portals, feeds). For each registered **address** it runs **tasks** —
singly or as an ordered **chain** of up to 10 steps — that fetch the listing,
clear obstructions, simplify the HTML, understand the page (AI or
deterministic heuristics), apply the site's own filters, and extract
structured news/events items into a **repository** that you can list,
search, export and audit. The pipeline follows the SSAFP shape:

```
WEBSCRAPE → SIMPLIFY → ANALYZE → FILTER → PRINT
```

Everything persists locally: SQLite (`neis.db`) for the catalog, executions
and repository; a per-address content tree on disk for artifacts.

## 2. Requirements and build

- Java 21 (the application targets class-file 65; a JDK 17 `java` on the
  PATH will not run it — the launcher scripts below pick JDK 21 for you).
- Maven 3.9+ to build.
- Optional: a Melious API key in `MELIOUS_API_KEY` to enable the AI (§9).

```bash
mvn -B package          # builds target/neis-*.jar and runs the test suite
```

## 3. Launching NEIS

Three ways in, all through the packaged jar:

**Interactive shell** (menu-friendly exploration, TUIs included):

```bash
./start.sh                      # shim to core/start.sh, no command → usage note
java -jar target/neis-*.jar     # interactive Spring Shell prompt
```

**One-shot commands** — the whole command as ONE quoted argument after `--`
(the non-interactive runner re-parses it, so nested quoting works). Where
the data lives depends on two variables:

- Plain `core/start.sh` works against `./neis.db` and `./core-work` in the
  current directory — a fresh, empty store.
- The core kit's store and content tree live in `core/work/`; to operate on
  what `core/run.sh` produced, export the same environment the kit sets
  before any one-shot command:

```bash
export NEIS_DB_PATH="$PWD/core/work/neis.db" \
       NEIS_CORE_WORK_DIR="$PWD/core/work" \
       NEIS_LOG_PATH="$PWD/core/work/logs/neis.log"

neis 'chain list'
neis 'address add --id ADDR-010 --name "ERA Learning" --type URL_NEWS --url "https://era.ec.europa.eu/news_en"'
core/start.sh --list            # shortcut for 'address list'
```

A shell helper keeps every later example short:

```bash
neis() { core/start.sh -- "$1"; }   # with the exports above in place
neis 'address show --id ADDR-006'
```

> Pointing `--neis.core.work-dir` at the kit without `--neis.db.path` (or
> vice versa) boots against a store that only knows part of the catalog —
> `Unknown address id: ADDR-00X`. Set both (the exports above do), or let
> `core/run.sh` set them for you.

**The core kit one-command run** — seeds addresses + selectors, then runs
the configured chain `core-chain-ssafp` (WEBSCRAPE→SIMPLIFY→ANALYZE→FILTER→
PRINT) end to end:

```bash
core/run.sh                 # run over every registered address
core/run.sh ADDR-006        # run over one address
core/run.sh --list          # show the registered address catalog
core/show.sh                # view the last run's results and artifacts
```

Everything the kit needs stays inside `core/work/` (database, content tree,
selector store, logs) and `core/results/`.

> The launcher enables the AI automatically when `MELIOUS_API_KEY` is set
> (environment or `~/.hermes/.env`). The MVP launcher (`mvp-chain`) is
> retired — do not look for it.

## 4. Concepts you will meet in every section

| Concept | Meaning |
|---|---|
| **Address** | A registered listing source (`ADDR-NNN`), with a kind (`URL_NEWS` / `URL_EVENTS`), a webpage type, and a verification status. Only **verified** addresses are eligible for tasks (US-04.3). |
| **Task type** | `WEBSCRAPE`, `SIMPLIFY`, `ANALYZE`, `AI`, `FILTER`, `PRINT`, `CHAIN` — what the executor and scheduler accept (`execution types` lists them). |
| **Chain** | An ordered list of 1..10 tasks with per-task parameters, run sequentially per address (US-08). |
| **Capability flags** | What ANALYZE learned about the page (PAGINATION, FILTERING) — a task that needs a capability only runs when it is flagged; otherwise it is skipped with a note (US-17.2). |
| **Capture / content** | Each run stores a capture; every task's output lands in the address's content tree and in `content_records` (US-11). |
| **Execution** | A record of one run (id `EXEC-*`), its outcome and provenance (US-05.3). |
| **Selector store** | The news/events CSS selectors identified for an address (AI, preconfigured, or user override), persisted so later runs skip the AI (US-12). |

## 5. Managing addresses

```bash
# Register (id auto-assigned if omitted)
neis 'address add --name "HaDEA News" --type URL_NEWS --url "https://hadea.ec.europa.eu/news_en" --description "Drupal news list with facets"'

# Inspect and edit
neis 'address list'
neis 'address show --id ADDR-006'
neis 'address edit --id ADDR-006 --name "HaDEA — News"'

# Reachability check (records VERIFIED/FAILED on the address; required before runs)
neis 'address verify --id ADDR-006'
neis 'address verify --all'

# Webpage type decides the retrieval strategy (US-06.5)
neis 'address webpage-type --id ADDR-006'                 # show
neis 'address webpage-type --id ADDR-003 --type spa'      # set (taxonomy: static, spa, spa-angular, spa-react, spa-vue, hybrid, other)

# Remove (its working selector goes too; execution/content history is kept)
neis 'address remove --id ADDR-010'
```

**AI-assisted registration** — you give the URL, the AI proposes
name/type/description/metadata, and *you confirm before anything is saved*:

```bash
neis 'address suggest --url "https://erasmus-plus.ec.europa.eu/news"'
```

**Preflight probe** — a lightweight entry-page check (static fetch, rendered
probe when it looks like an application shell) that classifies the address
into the webpage-type taxonomy and estimates the retrieval connector; both
are persisted as metadata:

```bash
neis 'address preflight --id ADDR-006'
```

**TUI** — the interactive screen for list/add/edit/remove, showing each
address's validation and verification status:

```bash
neis 'address tui'
```

Interaction rules in every TUI: empty input keeps the shown default, an
invalid value aborts that one operation (the screen is redrawn, nothing is
half-updated), a delete asks for an explicit `y`, and Ctrl-D / piped-EOF
exits cleanly.

## 6. Running tasks

```bash
# One task over one address (asynchronous; --wait blocks and shows the outcome)
neis 'execution run --task WEBSCRAPE --id ADDR-006 --wait'

# Task parameters (repeatable --param key=value)
neis 'execution run --task PRINT --id ADDR-006 --param max-items=5 --wait'
neis 'execution run --task WEBSCRAPE --id ADDR-006 --param max-items=50 --param filter.programme=EU4Health --wait'
neis 'execution run --task WEBSCRAPE --id ADDR-006 --param connector=http --wait'
neis 'execution run --task SIMPLIFY --id ADDR-006 --param variant=economy --wait'

# Everything the executor accepts, and the run history
neis 'execution types'
neis 'execution list --limit 10'
neis 'execution show --id EXEC-be85bc40-e4d7-42aa-a7de-8c70b6713a67'
```

Per-task parameters (anything else is refused loud):

| Task | Parameters |
|---|---|
| `WEBSCRAPE` | `max-items` (1..10000 extraction cap, overrides `neis.extraction.max-items`), `selector` (per-run CSS override, US-12.6), `connector` (pins the strategy: `fixtures`, `http` or `rendered`), `filter.<name>=<value>` (applies the site's own filter before extraction, US-06.6) |
| `SIMPLIFY` | `variant` = `full` (default) / `economy` / `lean` (US-07.6 tiered rendering) |
| `ANALYZE` | none |
| `AI` | none (fails loud if AI is not configured) |
| `FILTER` | none standalone; a chain's FILTER step carries `field`/`value` (US-17.3) |
| `PRINT`, `CHAIN` | `max-items` |

Notes:

- `filter.*` parameters and `selector` do **not** apply to feed sources
  (RSS/Atom addresses are parsed directly — US-06.8); a `connector`
  override on a feed is ignored with a log line.
- The `connector` override beats the address's webpage type for that run
  only.

## 7. Managing chains

```bash
# Create: steps are CODE or CODE:key=value;key2=value2, comma-separated, 1..10 of them
neis 'chain create --name "weekly-hadea" --description "Weekly HaDEA capture" --tasks "WEBSCRAPE,SIMPLIFY,ANALYZE,PRINT:max-items=10"'

# Inspect / run (ids are CHAIN-<uuid>; 'chain list' shows them)
neis 'chain list'
neis 'chain show --id CHAIN-3fdc8ba3-4646-4542-9940-fdbfa27fa89f'
neis 'chain run --id CHAIN-3fdc8ba3-4646-4542-9940-fdbfa27fa89f --address ADDR-006 --wait'

# Replace a definition (the list IS the order; empty options keep values)
neis 'chain update --id CHAIN-3fdc8ba3-4646-4542-9940-fdbfa27fa89f --tasks "WEBSCRAPE,SIMPLIFY,PRINT:max-items=20"'

# Delete (past executions keep their records)
neis 'chain delete --id CHAIN-3fdc8ba3-4646-4542-9940-fdbfa27fa89f'

# Full chain management screen: list, create, edit, reorder steps, delete
neis 'chain tui'
```

A chain-level filter criterion travels with the chain and is applied
offline by its FILTER step (US-17.3) — either inline (`--filter`) or from a
JSON spec file (`--filter-spec path/to/spec.json`).

## 8. The content tree

Every capture lands under the content root (`neis.core.work-dir`, the core
kit pins it to `core/work/`), organized by address / capture / task:

```bash
neis 'content list --address ADDR-006 --limit 10'
neis 'content tree --address ADDR-006'
```

`content tree` shows each capture's per-task folders and the fetch record;
the PRINT report artifact, for example, is at
`<capture>/content/PRINT/report.txt`. Artifacts mirror the database's
`content_records` (re-runs update in place), and every task folder is named
with the executor's task code.

## 9. The news/events repository

```bash
# List what was extracted, with provenance, filterable
neis 'news list --address ADDR-006 --kind news --from 2026-10-01 --limit 20'
neis 'news list --incomplete incomplete'        # rows missing mandatory metadata

# Full-text search (SQLite FTS5; all terms must match, ranked by relevance)
neis 'news search --q "EU4Health call" --kind news --from 2026-01-01'

# Dedup-aware history: re-encounters add sightings, never duplicate records (US-14.5)
neis 'news sightings --address ADDR-006'

# Provenance audit: rows whose execution/content reference no longer resolve (empty = healthy)
neis 'news provenance'

# Deterministic export (same data + filters ⇒ same file)
neis 'news export --format json --address ADDR-006 --out /tmp/hadea-news.json'
neis 'news export --format csv --from 2026-10-01 --to 2026-11-01 --out /tmp/october.csv'

# Prune a false positive (soft delete — excluded from list/search/export,
# provenance stays readable, re-extraction never resurrects; US-14.10)
neis 'news prune --id cn-1a2b3c4d5e6f7a8b --reason "quiz widget, not a news item"'

# Bulk prune (US-14.13): the news list filters select; DRY RUN by default —
# the command prints the matching records and writes nothing until --confirm
neis 'news prune-bulk --address ADDR-010 --kind news --incomplete incomplete'
neis 'news prune-bulk --ids cn-1a2b3c4d5e6f7a8b,cn-9f8e7d6c5b4a3f2e --reason "duplicate rows from the tracking-param defect" --confirm'
```

`news prune-bulk` is soft like the single prune: every affected row
records the reason, whole canonical groups go together (all rows
sharing the id, across addresses), and a selection matching nothing is
a no-op success. The same operation is exposed over REST
(`POST /api/news/prune`, `POST /api/news/prune-bulk` with a
`confirmed` flag) and MCP (`neis_prune_item`,
`neis_prune_news_bulk`).

Date filters accept ISO (`2026-07-16`) or the extractor's own form
(`Jul 16, 2026`); a typo in a bound is rejected, not ignored.

## 10. The management interface

```bash
neis 'manage'
```

One screen for the whole system, redrawn from live state after every
operation. Sections (keys shown at the prompt):

| Key | Section |
|---|---|
| `d` | **Dashboard** — AI status, addresses (verified/unverified/failed), chains, task types, execution records (active/failed, last run, recent failures), repository size with 24-hour growth, configured schedules and their next fire time |
| `a` | Addresses (the address TUI) |
| `c` | Chains (the chain TUI) |
| `e` | **Executions** — history, detail, re-run; act on a record by its list number |
| `r` | **Repository** — browse news, events and content |
| `n` / `g` | **Configuration** — the *effective* configuration (system property > application.yml > default, each layer marked) |
| `o` | **Logs** — recent operation lines |
| `q` | quit |

Navigation model: inside a section you act on records by **list number**;
ids (`EXEC-*`, `CONT-*`) are for the CLI commands.

## 11. The AI integration

The AI (exactly one provider: MeliousAI, `glm-5.3-flash`) is used by
ANALYZE/AI for classification, selector identification, capability
flagging and address correction, and by `address suggest` — always with
explicit decision-level `[AI]` log traces, and never with page bodies in
the log. Learnings are persisted with the address: the AI is needed the
first time, later runs take the stored fast path.

```bash
neis 'ai-status'        # shows whether the AI is configured/active
neis 'ai-ask'           # send a prompt directly to the provider
```

Enablement: `MELIOUS_API_KEY` in the environment (the launcher then passes
`--neis.ai.enabled=true` automatically), or set `neis.ai.enabled: true`
with `neis.ai.api-key-ref: MELIOUS_API_KEY` in configuration. The key
never lives in configuration files or the repository. Without the AI,
ANALYZE falls back to deterministic heuristics; a standalone `AI` task
fails loud instead of silently doing nothing.

## 12. Scheduled runs

Schedules are configuration, never hard-coded (US-05.2). Spring 6-field
cron; each entry fires the asynchronous executor and leaves an execution
record like a manual run:

```yaml
neis:
  schedule:
    entries:
      - name: hourly-hadea-news
        task: WEBSCRAPE
        address: ADDR-006
        cron: "0 5 * * * *"
      - name: nightly-chain
        task: CHAIN
        address: ADDR-007
        cron: "0 30 2 * * *"
```

The dashboard shows each entry's next fire time (and a cron error, if the
expression matches nothing).

## 13. Backing up the database

Since US-02.5 the store runs in **WAL journal mode**: recent commits can sit
in the `neis.db-wal` sidecar next to `core/data/neis.db` until SQLite
checkpoints them. A bare `cp neis.db backup.db` taken while the service is
running can therefore **silently lose recent commits**. Use one of:

- `sqlite3 core/data/neis.db ".backup /path/to/backup.db"` (online, safe), or
- `sqlite3 core/data/neis.db "VACUUM INTO '/path/to/backup.db'"`, or
- stop the service, then copy `neis.db` (a clean close removes the sidecars).

The `-wal`/`-shm` files appearing next to the store while the service runs
are normal; they are removed on a clean shutdown and must not be copied
individually.

## 14. Configuration reference

Externalized keys (defaults in `src/main/resources/application.yml`;
overrides via `--key=value` arguments, environment relaxed binding, or
application.yml):

| Key | Default | Purpose |
|---|---|---|
| `neis.db.path` | `./neis.db` | SQLite database location (env `NEIS_DB_PATH`) |
| `neis.core.work-dir` | `./core-work` | Content root for captures/artifacts (env `NEIS_CORE_WORK_DIR`) |
| `neis.log.path` | `./logs/neis.log` | Rolling log file (env `NEIS_LOG_PATH`); 10 MB rotation, 14 days retention |
| `neis.scrape.connector` | `static` | WEBSCRAPE retrieval: `static` (bundled fixtures, offline) or `live` (real HTTP); unknown values fail the boot |
| `neis.scrape.timeout-seconds` | `20` | Per-request timeout of the live fetch |
| `neis.scrape.max-bytes` | `10485760` | Response-body cap; the live fetch aborts mid-transfer past it |
| `neis.scrape.clearing.selectors` | `[]` | Page-specific obstructing-element CSS selectors removed at capture (popups/banners/ads the generic rules miss) |
| `neis.address.verification.timeout-seconds` | `10` | Reachability check timeout |
| `neis.selectors.preconfigured` | `[]` | Preconfigured CSS selectors consulted by ANALYZE before the AI |
| `neis.extraction.max-items` | *(absent = unlimited)* | Default extraction cap |
| `neis.execution.await-timeout-seconds` | `300` | How long CLI `--wait` blocks |
| `neis.schedule.entries` | `[]` | Scheduled runs (§12) |
| `neis.ai.enabled` / `provider` / `base-url` / `api-key-ref` / `model` | `false` / `melious` / Melious URL / `MELIOUS_API_KEY` / `glm-5.3-flash` | AI provider (§11) |
| `neis.ai.timeouts.default` / `neis.ai.timeouts.<operation>` | `240s` / (unset) | Per-operation AI call timeouts (US-13.8): `default` applies to every operation without an explicit value; operation keys: `generic`, `classification`, `selector-news`, `selector-events`, `capability-detection`, `capability-repair`, `filter-zone-selector`, `filter-criterion`, `filter-apply`, `registration`, `preflight`. A timed-out call falls back to the non-AI path; the HTTP client itself is hard-bounded at the longest configured value. |
| `neis.scrape.connector` | `static` | The SCRAPE connector: `static` (bundled fixtures), `live` (per-type HTTP/rendered retrieval), `cached` (US-06.14: serves the address's latest stored scrape — provenance-marked, fail-closed when nothing is stored). |
| `neis.scrape.cached-max-age` / `neis.scrape.fallback-to-stored` | (unset) / `false` | US-06.15/US-06.14: max age of served cached content (ISO-8601, e.g. `PT72H`; unset = unlimited; exceeded content fails the fetch naming age and bound) and the opt-in fallback of a FAILED static/live fetch to the latest stored content (never silent — provenance-marked). |

A configuration overview with every layer marked (override vs
from-configuration vs default) is in the management UI's configuration
section (`manage` → `n`).

## 15. Troubleshooting

| Symptom | Cause and remedy |
|---|---|
| `UnsupportedClassVersionError ... class file 65` | A JDK 17 `java` was used. Run through `core/start.sh` / `core/run.sh` (they pick JDK 21), or export `JAVA_HOME` to a JDK 21. |
| `database file is locked` (SQLITE_BUSY) right after a build | A lingering test JVM holds the SQLite file. Check `fuser <db path>`, wait it out, re-run. Transient, not a schema problem. |
| A command dies with `CommandNotFoundException` before running, exit 1, quoting complaint | Unbalanced double quotes in the command — the entry point refuses them with a clear error. Balance the quotes; double them (`""`) to embed a literal quote. |
| A `--` option seems ignored in one-shot runs | Pass the whole command as ONE quoted argument after `core/start.sh --`, and use long option forms (`--query`, not short options). Spring property overrides like `--neis.ai.enabled=true` must be separate arguments — the launcher does this for you. |
| `Unknown address id: ADDR-00X` | The run opened a different database. Point both store and work dir at the same kit: `--neis.core.work-dir` AND `--neis.db.path` (the kit sets both). |
| A task is skipped with a capability note | The address lacks the PAGINATION/FILTERING flag — run ANALYZE (or the full chain) to (re)flag it. |
| A config selector change seems inert | Once a validated selector is persisted in the store, the stored fast path keeps winning while it still extracts items; the change takes effect after the stored selector goes stale (US-12.6 semantics). |
| WEBSCRAPE refuses `selector`/`filter.*` | The address is a feed source (RSS/Atom parsed directly) — remove the parameter. |
| AI absent in ANALYZE output | `ai-status` to check; set `MELIOUS_API_KEY` and run through the launcher, or set `neis.ai.enabled: true` in configuration. Heuristic fallbacks keep deterministic runs working. |

---

*Companion documents: `ARCHITECTURE.md` (design), `neis-roadmap.html`,
`neis-architecture.html`, `neis-architecture-high-level.html` (diagrams),
`core/README.md` (run kit). Backlog: `joseluisll/neis-cli`, project #3.*
