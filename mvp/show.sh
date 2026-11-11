#!/usr/bin/env bash
# Show the results of the last NEIS MVP chain run.
#
#   mvp/show.sh                 # results of the last run
#   mvp/show.sh --raw           # full raw log of the last run (incl. logs)
#   mvp/show.sh --artifacts     # list the chain artifacts on disk
#   mvp/show.sh --items         # just the discovered news/event items
#   mvp/show.sh --list-runs     # all saved runs
set -euo pipefail

KIT_DIR="$(cd "$(dirname "$0")" && pwd)"
RESULTS_DIR="$KIT_DIR/results"
WORK_DIR="$KIT_DIR/work"

case "${1:-}" in
  --list-runs)
    # All saved runs: per-run results file paired with its raw log.
    for r in $(ls -1t "$RESULTS_DIR"/results-*.txt 2>/dev/null); do
      ts="${r##*results-}"; ts="${ts%.txt}"
      echo "$(basename "$r")  +  raw-$ts.log"
    done
    if [[ ! -e "${r:-}" ]]; then
      ls -1t "$RESULTS_DIR"/raw-*.log 2>/dev/null || echo "No runs yet."
    fi
    exit 0 ;;
  --artifacts)
    find "$WORK_DIR" -type f \( -name '*.html' -o -name '*.json' \) \
      | sed "s|$KIT_DIR/||" | LC_ALL=C sort
    exit 0 ;;
esac

LAST_RAW="$(ls -1t "$RESULTS_DIR"/raw-*.log 2>/dev/null | head -1 || true)"

if [[ "${1:-}" == "--raw" ]]; then
  if [[ -n "$LAST_RAW" ]]; then cat "$LAST_RAW"; else echo "No runs yet. Run mvp/run.sh first."; exit 1; fi
  exit 0
fi

if [[ ! -s "$RESULTS_DIR/last-run.txt" ]]; then
  echo "No results yet. Run mvp/run.sh first."
  exit 1
fi

if [[ "${1:-}" == "--items" ]]; then
  # Only the item lines: "N. <title>" plus their date/link/desc lines.
  grep -E '^  [0-9]+\. |^     (date|link|desc): ' "$RESULTS_DIR/last-run.txt" || echo "(no items)"
  exit 0
fi

cat "$RESULTS_DIR/last-run.txt"
