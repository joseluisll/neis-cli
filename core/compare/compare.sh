#!/usr/bin/env bash
# Compare two kit runs (the retired MVP kit baseline vs this kit's
# core-chain-ssafp run) and prove the results are equal.
#
#   core/compare/compare.sh <baseline-results.txt> <core-results.txt> <outdir>
#
# The two files differ only in what MUST differ:
#   - the report header names the chain that produced it
#     ("MVP chain for ADDR-..." vs "core-chain-ssafp for ADDR-..."),
#   - artifact paths embed each kit's content root.
# Everything else — byte counts, analysis, capabilities, filters, the
# applied criterion, every discovered item with its date and link —
# must be identical. The script normalizes exactly those two
# differences and diffs the rest.
set -euo pipefail

BASELINE="$1"
CORE="$2"
OUT="${3:-$(dirname "$0")}"

normalize() {
  # 1. Header: name what produced the report as "CHAIN", not which kit.
  # 2. Content-root paths: <...>/work/ADDR-.../... -> <WORK>/ADDR-.../...
  sed -E \
    -e 's/^(MVP chain for|core-chain-ssafp for)/CHAIN for/' \
    -e 's#(^|[ =:])[^ ]*/(work|neis-mvp)/#\1<WORK>/#g' \
    "$1"
}

normalize "$BASELINE" > "$OUT/baseline-normalized.txt"
normalize "$CORE"     > "$OUT/core-normalized.txt"

if diff -u "$OUT/baseline-normalized.txt" "$OUT/core-normalized.txt" \
    > "$OUT/unified-diff.txt"; then
  echo "IDENTICAL after normalization: $OUT/unified-diff.txt is empty."
else
  echo "DIFFERENCES found - see $OUT/unified-diff.txt"
fi
