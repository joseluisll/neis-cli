# NEIS MVP run kit

One-command way to run the NEIS MVP (issue #90): the predefined static
chain **SCRAPE → SIMPLIFY → ANALYZE → FILTER → PRINT** over the static
address catalog (issue #62 seed data, ADDR-001..ADDR-009), with the
static fixtures bundled in the application jar (no network access) and
AI classification/selector identification via MeliousAI.

## Run

```bash
mvp/run.sh                 # whole static catalog (all nine addresses)
mvp/run.sh ADDR-003        # one address
mvp/run.sh --list          # show the static address catalog
mvp/run.sh ADDR-003 --no-ai
```

- `run.sh` delegates to the repo-root `start.sh`, which selects JDK 21,
  picks up `MELIOUS_API_KEY` (environment or `~/.hermes/.env`), and
  builds the jar if it is missing.
- AI is on by default when the key is available; stored selectors (see
  below) skip the AI for addresses already analyzed.

## Show the results

```bash
mvp/show.sh                # results of the last run (chain PRINT output)
mvp/show.sh --items        # just the discovered news/event items
mvp/show.sh --artifacts    # all chain artifacts on disk
mvp/show.sh --raw          # full log of the last run (Spring logs incl.)
mvp/show.sh --list-runs    # every saved run
```

Every run keeps its results and its log together under `mvp/results/`:
a per-run pair `results-<timestamp>.txt` (extracted chain output) +
`raw-<timestamp>.log` (full log incl. Spring/[AI] lines), plus the
stable `last-run.txt` copy of the latest run's results.

## What is in the kit

```
mvp/
├── run.sh        the runner (JDK 21, credentials, build, results capture)
├── show.sh       the results viewer
├── work/         chain work dir, created on first run
│   ├── ADDR-00X/ SCRAPE (scrape*.html) and SIMPLIFY (simplified*.html)
│   │             artifacts written as real HTML5 files
│   └── selectors/  per-address selector store; seeded from the repo's
│                   neis-mvp/selectors on first run, so the chain works
│                   without the AI (AI-identified selectors are stored
│                   with their source for reuse)
└── results/      per-run pair results-<TS>.txt + raw-<TS>.log,
│                  plus the stable last-run.txt
```

The static data itself (the real Playwright-rendered page fixtures of
the seed addresses, including the captured filtered responses of the
US-06.6 slice) is packaged inside the jar from
`src/main/resources/mvp/samples/` — nothing to install here. Fixture
provenance and refresh instructions are in that directory's README.

## Notes

- If sources changed since the last build, `run.sh` warns; refresh with
  `mvn -B package` (tests included).
- Without `MELIOUS_API_KEY` the chain degrades gracefully: fallback
  classification and heuristic selectors, PRINT marks the analysis as
  fallback.
- Full behavior of the chain steps: repo-root `start.sh` header and
  `ARCHITECTURE.md`; requirement mapping in GitHub issue #90.
