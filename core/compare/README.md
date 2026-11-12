# Comparison: retired MVP kit vs NEIS Core v1.0 kit

Evidence that the Core Platform's configured chain `core-chain-ssafp`
reproduces the retired MVP chain's results. All runs use the same nine
seed addresses (issue #62 set + the US-17 additions), the same bundled
fixtures and the same AI (Melious, glm-5.3-flash, temperature 0), and
start from the same seeded selector store.

## Files

- `baseline-results.txt` — MVP kit baseline (the retired `mvp-chain all`
  command, 2026-11-12, AI on).
- `baseline2-results.txt` — the same MVP command run again, to measure
  the MVP kit's own run-to-run stability.
- `core-chain-results.txt` — the Core v1.0 kit (`core/run.sh`, chain
  `core-chain-ssafp`, same day, AI on).
- `unified-diff.txt` — the two full result files after normalizing
  exactly what MUST differ (see below).
- `mvp-self-diff.txt` — MVP run 1 vs MVP run 2, normalized the same
  way: the baseline for judging whether any difference is specific to
  the kit change.
- `compare.sh` — reproduces both diffs.

## Result

After normalization the core-kit run and the MVP baseline differ ONLY
inside the FILTER block's AI free text — 3 hunks, 9 line pairs
(`unified-diff.txt`): filter field naming/phrasing (ADDR-005 date
labels, ADDR-007's 10-vs-8 extracted fields, ADDR-008's 2-vs-3 extracted
fields and the criterion label "Product type" vs "Category"). The
applied filter REQUEST is identical everywhere
(`facets[category]=human` etc.) and so are the filtered listings.

`mvp-self-diff.txt` (MVP run 1 vs MVP run 2, 4 line pairs) shows the
retired MVP kit drifting the SAME way between its own consecutive runs
(same ADDR-005 labels, same ADDR-007 field count): this is the
documented temperature-0 instability of the AI's filter extraction, not
a difference between the kits. Every deterministic result is identical
in all three runs: scrape/simplify byte counts, classification, selector
source, capability flags, page URL patterns, pages scraped, the applied
filter request per address, and every discovered item with its date and
link (including the filtered PRINT(f) listings).

What the normalizer may legitimately rewrite:

1. The report header names the chain that produced it
   (`MVP chain for ADDR-...` vs `core-chain-ssafp for ADDR-...`).
2. Artifact paths embed each kit's content root (`<WORK>/`).

## How to reproduce

```bash
# the kit
core/run.sh && cp core/results/results-<ts>.txt core/compare/core-chain-results.txt
# the comparison
core/compare/compare.sh <baseline-results.txt> <core-chain-results.txt> core/compare
```
