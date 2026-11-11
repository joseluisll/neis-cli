#!/usr/bin/env bash
# NEIS MVP run kit — one-command runner (issue #90).
#
# Delegates to the repo-root start.sh (JDK 21, Melious credentials,
# build-if-missing) but keeps EVERYTHING this kit needs inside mvp/:
#
#   mvp/work/            chain work dir (SCRAPE/SIMPLIFY artifacts, selectors)
#   mvp/work/selectors/  seeded from the repo's neis-mvp/selectors on first
#                        run, so the chain works without the AI
#   mvp/results/         full raw log + extracted chain results per run
#
# Usage (same targets as start.sh):
#   mvp/run.sh                 # whole static catalog (all addresses)
#   mvp/run.sh ADDR-003        # one address
#   mvp/run.sh --list          # show the static address catalog
#   mvp/run.sh ADDR-003 --no-ai
#
# After it finishes, view the results with mvp/show.sh.
set -euo pipefail

KIT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="$(dirname "$KIT_DIR")"

WORK_DIR="$KIT_DIR/work"
RESULTS_DIR="$KIT_DIR/results"
mkdir -p "$WORK_DIR" "$RESULTS_DIR"

# Keep the chain's artifacts and selector store inside the kit
# (Spring Boot relaxed binding: NEIS_MVP_WORK_DIR -> neis.mvp.work-dir).
export NEIS_MVP_WORK_DIR="$WORK_DIR"
# Keep the run kit's SQLite database inside the kit's work dir too, not
# CWD-relative in the repo root (US-02).
export NEIS_DB_PATH="$WORK_DIR/neis.db"

# Seed the selector store so AI is optional from the very first run.
if [[ -d "$ROOT_DIR/neis-mvp/selectors" ]] && ! ls "$WORK_DIR"/selectors/*.json >/dev/null 2>&1; then
  mkdir -p "$WORK_DIR/selectors"
  cp -R "$ROOT_DIR"/neis-mvp/selectors/*.json "$WORK_DIR/selectors/" 2>/dev/null || true
fi

# Warn when sources are newer than the packaged jar (start.sh only builds
# when the jar is missing entirely).
JAR="$(ls -t "$ROOT_DIR"/target/neis-*.jar 2>/dev/null | head -1 || true)"
if [[ -n "$JAR" ]] && [[ -n "$(find "$ROOT_DIR/src" -newer "$JAR" -print -quit 2>/dev/null)" ]]; then
  echo "NOTE: sources changed since the jar was built - run 'mvn -B package' to refresh." >&2
fi

TS="$(date +%Y%m%d-%H%M%S)"
RAW="$RESULTS_DIR/raw-$TS.log"

if [[ "${1:-}" == "--list" ]]; then
  # Catalog listing only - do not touch the chain results.
  exec "$ROOT_DIR/start.sh" --list
fi

echo "Running the NEIS MVP chain (output also saved to ${RAW#$ROOT_DIR/})..."
set -o pipefail
"$ROOT_DIR/start.sh" "$@" 2>&1 | tee "$RAW"

# Extract the chain results (PRINT output) from the raw log into a
# stable last-run file for mvp/show.sh. Spring Boot log lines carry an
# ISO timestamp prefix; the chain output starts at a known marker.
# The same extraction is kept per run (results-<TS>.txt) alongside the
# per-run raw log (raw-<TS>.log), so every run keeps its results and
# its log together.
awk '/^(MVP chain for|Static MVP address catalog:)/{found=1} found' "$RAW" \
  | grep -vE '^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}\.[0-9]+' \
  > "$RESULTS_DIR/last-run.txt" || true

if [[ -s "$RESULTS_DIR/last-run.txt" ]]; then
  cp "$RESULTS_DIR/last-run.txt" "$RESULTS_DIR/results-$TS.txt"
  echo
  echo "Results saved: mvp/results/last-run.txt  (view: mvp/show.sh)"
  echo "Run record:    mvp/results/results-$TS.txt + ${RAW#$KIT_DIR/}"
else
  echo
  echo "WARNING: no chain output detected in the log - check $RAW" >&2
  exit 1
fi
