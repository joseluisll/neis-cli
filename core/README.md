# NEIS Core v1.0 kit

One-command way to run the NEIS Core Platform: the configured chain
**core-chain-ssafp** — `WEBSCRAPE -> SIMPLIFY -> ANALYZE -> FILTER ->
PRINT`, the SSAFP stages of the retired MVP chain — over the registered
address catalog, through the platform's own machinery (asynchronous
executor, execution records, eligibility gate, US-11 content tree).

Two kinds of network traffic happen, deliberately:

- Seeding VERIFIES every address over real HTTP (`address verify`,
  US-04.2) — only verified addresses are eligible for the executor.
- At chain runtime the SCRAPE connector serves the static HTML5
  fixtures bundled in the application jar (real pages captured with
  Playwright, issue #62 / US-17); no live fetching happens inside the
  chain.

AI classification/selector identification goes through MeliousAI when
the key is available, and the seeded selector store makes the AI
optional from the very first run.

## Reproducing the scenario

Prerequisites: JDK 21 (`/usr/lib/jvm/java-21-openjdk-amd64` on this
host — the host default `java` is 17 and crashes with
`UnsupportedClassVersionError`) and a Melious API key in the
environment or `~/.hermes/.env` (variable `MELIOUS_API_KEY`).

```bash
cd /home/jllopez/code/neis-cli

mvn -B package             # build the jar once (tests included)

core/run.sh                # seed + run core-chain-ssafp over all 9
                           # seed addresses (~15-20 min with AI on)
core/show.sh               # results of the last run
```

That is the whole scenario. `run.sh` is idempotent and self-seeding:
on a fresh machine it registers the addresses, verifies them (with
retries for sites that answer 429), creates the chain and runs it. To
start completely from scratch, delete `core/work/` (the kit's SQLite
database, content tree and selector store live there) and run
`core/run.sh` again. To run a single address: `core/run.sh ADDR-003`.

Expected outcome: 9 successful chain runs (one execution record per
address), per-address reports in `core/results/last-run.txt`, and the
equality evidence of `compare/` (see below). With no AI key the run
still works — heuristics replace the AI and the results say so.

## What is defined: the chain

Exactly one chain is defined, named **core-chain-ssafp** (id
`CHAIN-3e927f6d-93ba-486f-bcc7-c613f51541a4` in the kit's database;
ids are generated, so a recreated chain gets a new one):

```
chain create --name core-chain-ssafp \
  --description "Core platform SSAFP chain: WEBSCRAPE - SIMPLIFY - \
ANALYZE - FILTER - PRINT (ex MVP chain)" \
  --tasks "WEBSCRAPE,SIMPLIFY,ANALYZE,FILTER,PRINT"
```

Steps, in order (each task type runs the pipeline up to and including
its own stage):

| # | Task code | What it does |
|---|---|---|
| 1 | `WEBSCRAPE` | fetch (static fixture) and store the listing HTML5 |
| 2 | `SIMPLIFY` | cleanse to action/data elements, 100 KB cap |
| 3 | `ANALYZE` | classification, selector, capability detection (pagination/filtering, AI + heuristics) |
| 4 | `FILTER` | filter-zone selector, filter definitions, apply ONE criterion |
| 5 | `PRINT` | the whole pipeline; extracts items and persists the readable report |

Per-task parameters (US-08.3): `PRINT` and `CHAIN` accept `max-items`
(1..10000, default 50) and `max-pages` (1..100, default 10); the other
task types refuse unknown parameters. core-chain-ssafp's steps carry no
parameters, so it runs with the defaults — the same limits the retired
MVP chain used. Inspect the chain any time:

```bash
core/start.sh -- 'chain list'
core/start.sh -- 'chain show --id <chain id>'
core/start.sh -- 'execution list'          # one record per chain run
core/start.sh -- 'content tree --address ADDR-003'
```

### Chain filters (offline filtering)

A chain can carry its own filter criterion — chosen by you at chain
creation, changed by redefining the chain, stored with the chain (not
the address). The address's filter identification (zone selector +
catalogue of every field with ALL its possible values, named as the
site labels them) is extracted once — by the AI or the generic parser —
and persisted in the selector store; from then on the filter is applied
FULLY OFFLINE:

```bash
core/start.sh -- 'chain create --name hadea-past-events \
  --tasks "WEBSCRAPE,SIMPLIFY,ANALYZE,FILTER,PRINT" \
  --filter "field=Event status;value=Past"'
# or, structured:
#   --filter-spec core/seed/filters/hadea-past-events.json  with
#   {"filters": [{"field": "Event status", "value": "Past"}]}
```

At run time the spec is coherence-matched against the address's stored
catalogue and the connector's servable requests; a match is applied
before the response (the report shows `APPLY: field = value [request]`
and the `PRINT(f)` filtered listing), a mismatch is reported and
skipped — never a chain failure. Without `--filter`, the AI chooses the
criterion when configured; with no AI and no spec, the catalogue is
still built and stored, and application is skipped.

## Commands

| Script / command | Purpose |
|---|---|
| `core/run.sh` | seed (addresses, selectors, chain) + run the chain over every seed address |
| `core/run.sh ADDR-003` | run the chain over one address |
| `core/run.sh --list` | show the registered address catalog |
| `core/show.sh` | results of the last run |
| `core/show.sh --items` | only the discovered items |
| `core/show.sh --artifacts` | the content-tree files on disk |
| `core/show.sh --raw` | full raw log of the last run (incl. `[AI]` traces) |
| `core/show.sh --list-runs` | all saved runs |
| `core/start.sh -- '<command>'` | any platform CLI command (`address …`, `chain …`, `execution …`, `content …`, `ai-status`, `about`) |
| `core/compare/compare.sh` | rebuild the MVP-vs-kit comparison diffs |

The repo-root `./start.sh` is a shim to `core/start.sh`. AI is enabled
automatically whenever `MELIOUS_API_KEY` is available (it is passed as
a separate `--neis.ai.enabled=true` argument, which is how Spring Boot
picks it up).

## Layout

```
core/
  start.sh              launcher (JDK 21 + AI credentials + jar)
  run.sh                seed + run core-chain-ssafp, collect reports
  show.sh               view the results of the last run
  seed/addresses.tsv    the address catalog the kit registers (9 rows)
  seed/selectors/       the seeded selector store (AI optional)
  compare/              evidence: MVP-kit baseline vs this kit's run
  work/                 runtime: content root + neis.db (gitignored)
  results/              per-run raw log + results (gitignored)
```

Each address run leaves an execution record, its artifacts in the
content tree, and the readable report as a PRINT task output:
`core/work/<address>/<capture>/content/PRINT/report.txt` — that
report is what `results/last-run.txt` collects, separated by the same
divider the retired MVP command printed between per-address renders.

## Comparison with the MVP kit (retired)

`compare/` holds the evidence that the Core Platform reproduces the
MVP kit's results: the MVP baseline run (`mvp-chain all` over the same
nine addresses, same fixtures, same AI) and this kit's
core-chain-ssafp run, side by side with a normalized diff — see
`compare/README.md`. Deterministic results are identical in every
dimension; the only diffs are the AI's free-text filter-field phrasing,
the same drift the MVP baseline shows between its own consecutive runs.
The MVP chain class and the `mvp/` run kit were unwired and removed in
the same change; the pipeline lives on as `PipelineService` behind the
task types (`WEBSCRAPE`, `SIMPLIFY`, `ANALYZE`, `FILTER`, `PRINT`).
