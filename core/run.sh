#!/usr/bin/env bash
# NEIS Core v1.0 run kit — one-command runner for the configured chain.
#
# Chain: core-chain-ssafp = WEBSCRAPE -> SIMPLIFY -> ANALYZE -> FILTER ->
# PRINT (created on first run from this kit's definition, issue-equivalent
# of the retired MVP chain). Each address run goes through the platform's
# asynchronous executor (execution records, eligibility gate, per-address
# serialization) and leaves its artifacts in the US-11 content tree plus
# a PRINT report artifact (content/<capture>/content/PRINT/report.txt).
#
#   core/run.sh                 # run the chain over every seed address
#   core/run.sh ADDR-003        # run it over one address
#   core/run.sh --list          # show the registered address catalog
#
# Everything stays inside the kit:
#   core/work/       content root (artifacts, selector store, neis.db)
#   core/work/selectors/  seeded from core/seed/selectors on first run
#   core/results/    per-run raw log + extracted chain results
set -euo pipefail

KIT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="$(dirname "$KIT_DIR")"
CHAIN_NAME="core-chain-ssafp"
CHAIN_STEPS="WEBSCRAPE,SIMPLIFY,ANALYZE,FILTER,PRINT"
CHAIN_DESCRIPTION="Core platform SSAFP chain: WEBSCRAPE - SIMPLIFY - ANALYZE - FILTER - PRINT (ex MVP chain)"

WORK_DIR="$KIT_DIR/work"
RESULTS_DIR="$KIT_DIR/results"
mkdir -p "$WORK_DIR" "$RESULTS_DIR"

# Keep the pipeline's artifacts and selector store inside the kit
# (Spring Boot relaxed binding: NEIS_CORE_WORK_DIR -> neis.core.work-dir).
export NEIS_CORE_WORK_DIR="$WORK_DIR"
# Keep the kit's SQLite database inside the kit's work dir too (US-02).
export NEIS_DB_PATH="$WORK_DIR/neis.db"
# Keep the rolling log file inside the kit as well (US-15.2 review
# finding, 2026-11-14): ./logs/neis.log otherwise resolves against the
# caller's working directory.
export NEIS_LOG_PATH="$WORK_DIR/logs/neis.log"

cli() { "$ROOT_DIR/core/start.sh" -- "$1"; }

# ---------------------------------------------------------------------------
# Seed 1: selector store, so the AI is optional from the very first run.
if ! ls "$WORK_DIR"/selectors/*.json >/dev/null 2>&1; then
  mkdir -p "$WORK_DIR/selectors"
  cp -R "$KIT_DIR"/seed/selectors/*.json "$WORK_DIR/selectors/" 2>/dev/null || true
fi

# ---------------------------------------------------------------------------
# Seed 2: the address catalog (issue #62 set + the US-17 additions).
# Idempotent: already-registered ids are left untouched (catalog edits
# never happen from the kit). Registered addresses carry their issue
# description.
SEED_FILE="$KIT_DIR/seed/addresses.tsv"
while IFS=$'\t' read -r id type name url desc; do
  [[ "$id" =~ ^ADDR- ]] || continue
  if ! cli "address show --id $id" >/dev/null 2>&1; then
    cli "address add --id $id --name \"$name\" --type $type --url \"$url\" --description \"$desc\"" >/dev/null
    echo "Seeded address $id"
  fi
done < "$SEED_FILE"

# Verify (US-04.2): every address must be VERIFIED to be eligible. Real
# HTTP checks; EU sites occasionally answer 429 to back-to-back probes,
# so failed ids are retried a few times before giving up.
verify_all() {
  cli "address verify --all" | awk '/^  ADDR-/{print $1, $2}'
}
FAILED_IDS="$(verify_all | awk '$2 == "FAILED" {print $1}')"
for attempt in 1 2 3; do
  [[ -z "$FAILED_IDS" ]] && break
  sleep 20
  NEW_FAILED=""
  for id in $FAILED_IDS; do
    OUT="$(cli "address verify --id $id" || true)"
    if ! grep -qE 'VERIFIED \(HTTP' <<<"$OUT"; then NEW_FAILED="$NEW_FAILED $id"; fi
  done
  FAILED_IDS="$NEW_FAILED"
done
if [[ -n "$FAILED_IDS" ]]; then
  echo "WARNING: still unverified after retries:$FAILED_IDS — those addresses" >&2
  echo "are not eligible (US-04.3) and will be skipped by the run." >&2
fi

# ---------------------------------------------------------------------------
# Seed 3: the chain definition itself (idempotent).
if ! cli "chain list" | grep -q "'$CHAIN_NAME'"; then
  cli "chain create --name $CHAIN_NAME --description \"$CHAIN_DESCRIPTION\" --tasks \"$CHAIN_STEPS\""
fi
CHAIN_ID="$(cli "chain list" | awk -F"'" -v name="'$CHAIN_NAME'" 'index($0, name) {gsub(/^[ \t]+/, "", $1); print $1; exit}')"
if [[ -z "$CHAIN_ID" ]]; then
  echo "ERROR: chain '$CHAIN_NAME' exists but its id could not be resolved from 'chain list'." >&2
  exit 1
fi

if [[ "${1:-}" == "--list" ]]; then
  exec "$ROOT_DIR/core/start.sh" --list
fi

# ---------------------------------------------------------------------------
# Targets: one address id, or every seed address (kit order).
declare -a TARGETS=()
if [[ -n "${1:-}" ]]; then
  TARGETS=("$1")
else
  while IFS=$'\t' read -r id _type _name _url _desc; do
    [[ "$id" =~ ^ADDR- ]] && TARGETS+=("$id")
  done < "$SEED_FILE"
fi

TS="$(date +%Y%m%d-%H%M%S)"
RAW="$RESULTS_DIR/raw-$TS.log"
echo "Running chain $CHAIN_NAME ($CHAIN_ID) over: ${TARGETS[*]}"
echo "Raw log: ${RAW#$ROOT_DIR/}"

: > "$RESULTS_DIR/last-run.txt"
set -o pipefail
{
  FIRST=true
  for ADDR in "${TARGETS[@]}"; do
    echo "### chain run: $CHAIN_NAME over $ADDR"
    OUT="$(cli "chain run --id $CHAIN_ID --address $ADDR --wait" 2>&1)" || true
    echo "$OUT"
    if ! grep -q 'status : SUCCESS' <<<"$OUT"; then
      echo "### WARNING: run over $ADDR did not succeed (see above)" >&2
      continue
    fi
    # The PRINT step persisted the readable report in the content tree
    # (US-11); it IS the chain result for this address. Reports are
    # separated by the divider the retired MVP command printed between
    # per-address renders.
    REPORT="$(ls -t "$WORK_DIR/$ADDR"/*/content/PRINT/report.txt 2>/dev/null | head -1 || true)"
    if [[ -n "$REPORT" ]]; then
      if [[ "$FIRST" == false ]]; then
        printf '\n%s\n' "$(printf '=%.0s' {1..72})" >> "$RESULTS_DIR/last-run.txt"
      fi
      FIRST=false
      cat "$REPORT" >> "$RESULTS_DIR/last-run.txt"
    else
      echo "### WARNING: no PRINT report found for $ADDR under $WORK_DIR" >&2
    fi
  done
} 2>&1 | tee "$RAW"

# Restore the plain results (tee captured the progress echoes too):
# the results file only holds the reports.
if [[ -s "$RESULTS_DIR/last-run.txt" ]]; then
  cp "$RESULTS_DIR/last-run.txt" "$RESULTS_DIR/results-$TS.txt"
  echo
  echo "Results saved: core/results/last-run.txt  (view: core/show.sh)"
  echo "Run record:    core/results/results-$TS.txt + ${RAW#$KIT_DIR/}"
else
  echo
  echo "WARNING: no chain results collected - check $RAW" >&2
  exit 1
fi
