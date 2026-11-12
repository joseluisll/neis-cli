# NEIS Core v1.0 kit

One-command way to run the NEIS Core Platform: the configured chain
**core-chain-ssafp** — `WEBSCRAPE -> SIMPLIFY -> ANALYZE -> FILTER ->
PRINT`, the SSAFP stages of the retired MVP chain — over the registered
address catalog, through the platform's own machinery (asynchronous
executor, execution records, eligibility gate, US-11 content tree).
Static fixtures are bundled in the application jar (no network access
at chain runtime); AI classification/selector identification goes
through MeliousAI when the key is available, and the seeded selector
store makes the AI optional from the very first run.

## Run

```bash
core/run.sh                 # the chain over every seed address
core/run.sh ADDR-003        # the chain over one address
core/run.sh --list          # show the registered address catalog
core/show.sh                # results of the last run
core/show.sh --items        # just the discovered news/event items
```

- `run.sh` seeds the kit idempotently: the selector store
  (`core/seed/selectors`), the address catalog (`core/seed/addresses.tsv`,
  the issue #62 set + the US-17 additions, with real HTTP verification
  and 429 retries) and the chain definition itself.
- `start.sh` is the launcher (JDK 21, `MELIOUS_API_KEY` from the
  environment or `~/.hermes/.env`, build-if-missing); the repo-root
  `start.sh` is a shim to it. Any other CLI command goes through
  `core/start.sh -- '<command>'`.
- Each address run leaves an execution record (`execution list/show`),
  its artifacts in the content tree (`content tree --address ADDR-003`)
  and the readable report as a PRINT task output:
  `core/work/<address>/<capture>/content/PRINT/report.txt` — that
  report is what `results/last-run.txt` collects.

## Layout

```
core/
  start.sh              launcher (JDK 21 + AI credentials + jar)
  run.sh                seed + run core-chain-ssafp, collect reports
  show.sh               view the results of the last run
  seed/addresses.tsv    the address catalog the kit registers
  seed/selectors/       the seeded selector store (AI optional)
  compare/              evidence: MVP-kit baseline vs this kit's run
  work/                 runtime: content root + neis.db (gitignored)
  results/              per-run raw log + results (gitignored)
```

## Comparison with the MVP kit (retired)

`compare/` holds the evidence that the Core Platform reproduces the
MVP kit exactly: the MVP baseline run (`mvp-chain all` over the same
nine addresses, same fixtures, same AI) and this kit's
core-chain-ssafp run, side by side with a normalized diff — see
`compare/README.md`. The MVP chain class and the `mvp/` run kit were
unwired and removed in the same change; the pipeline lives on as
`PipelineService` behind the task types (`WEBSCRAPE`, `SIMPLIFY`,
`ANALYZE`, `FILTER`, `PRINT`).
