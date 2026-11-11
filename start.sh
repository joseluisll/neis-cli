#!/usr/bin/env bash
# NEIS MVP chain launcher (issue #90).
#
# Runs the predefined static chain SCRAPE -> SIMPLIFY -> ANALYZE -> PRINT
# from the packaged jar, with the right JDK and AI credentials.
#
# Usage:
#   ./start.sh                     # whole static catalog (all addresses)
#   ./start.sh ADDR-003            # one address (ADDR-001..ADDR-004)
#   ./start.sh ADDR-003 --no-ai    # force AI off (works once selectors
#                                  # are stored under neis-mvp/selectors/)
#   ./start.sh --list              # show the static address catalog
#
# AI is enabled automatically when MELIOUS_API_KEY is available (read from
# the environment or ~/.hermes/.env); ANALYZE then uses the AI prompt
# catalog. Once a working selector is stored for an address, the chain
# skips the AI entirely on later runs.
set -euo pipefail
cd "$(dirname "$0")"

# JDK 21 (the host default java is 17; the project targets class-file 65).
# A pre-existing JAVA_HOME is honored only if it really is a JDK 21+.
JAVA_HOME_CANDIDATE="${JAVA_HOME:-}"
JAVA_HOME_DEFAULT=/usr/lib/jvm/java-21-openjdk-amd64
java_major() { "$1" -version 2>&1 | sed -n 's/.*version "\([0-9]*\).*/\1/p'; }
if [[ -z "$JAVA_HOME_CANDIDATE" ]] || [[ "$(java_major "$JAVA_HOME_CANDIDATE/bin/java" 2>/dev/null || echo 0)" -lt 21 ]]; then
  JAVA_HOME_CANDIDATE="$JAVA_HOME_DEFAULT"
fi
export JAVA_HOME="$JAVA_HOME_CANDIDATE"
export PATH="$JAVA_HOME/bin:$PATH"

# AI credentials: environment first, then the Hermes env file
if [[ -z "${MELIOUS_API_KEY:-}" && -f "$HOME/.hermes/.env" ]]; then
  line="$(grep -E '^MELIOUS_API_KEY=' "$HOME/.hermes/.env" | head -1 || true)"
  [[ -n "$line" ]] && export MELIOUS_API_KEY="${line#MELIOUS_API_KEY=}"
fi

AI_ARGS=()
if [[ -n "${MELIOUS_API_KEY:-}" ]]; then
  AI_ARGS=(--neis.ai.enabled=true)
fi

# Build once if the jar is missing
JAR="$(ls -t target/neis-*.jar 2>/dev/null | head -1 || true)"
if [[ -z "$JAR" ]]; then
  echo "No packaged jar found - building..."
  mvn -B -q -DskipTests package
  JAR="$(ls -t target/neis-*.jar | head -1)"
fi

TARGET="all"
if [[ "${1:-}" == "--list" ]]; then
  exec java -Dspring.shell.interactive.enabled=false -jar "$JAR" mvp-addresses
fi
if [[ "${1:-}" == "--no-ai" ]]; then
  AI_ARGS=(); shift || true
elif [[ -n "${1:-}" ]]; then
  TARGET="$1"; shift
  if [[ "${1:-}" == "--no-ai" ]]; then
    AI_ARGS=(); shift || true
  fi
fi

exec java -Dspring.shell.interactive.enabled=false -jar "$JAR" \
  mvp-chain "$TARGET" "${AI_ARGS[@]}" "$@"
