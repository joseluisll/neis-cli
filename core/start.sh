#!/usr/bin/env bash
# NEIS Core v1.0 platform launcher.
#
# Runs the packaged jar with the right JDK and AI credentials; every
# actual command goes through the passthrough below. The MVP launcher
# command (mvp-chain) is retired — chain runs go through core/run.sh,
# which drives the configured chain core-chain-ssafp.
#
# Usage:
#   core/start.sh --list              # show the registered addresses
#   core/start.sh -- 'address add --id ADDR-001 --name "..." --type URL_NEWS --url "..."'
#   core/start.sh -- 'chain list'
#
# AI is enabled by setting neis.ai.enabled=true as a SEPARATE argument
# (Spring Boot lifts --key=value arguments into the environment) when
# MELIOUS_API_KEY is available (environment or ~/.hermes/.env).
set -euo pipefail
cd "$(dirname "$0")/.."

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

# Build once if the jar is missing
JAR="$(ls -t target/neis-*.jar 2>/dev/null | head -1 || true)"
if [[ -z "$JAR" ]]; then
  echo "No packaged jar found - building..."
  mvn -B -q -DskipTests package
  JAR="$(ls -t target/neis-*.jar | head -1)"
fi

# Warn when sources are newer than the packaged jar (stale-jar guard).
if [[ -n "$(find src pom.xml -newer "$JAR" -print -quit 2>/dev/null)" ]]; then
  echo "NOTE: sources changed since the jar was built - run 'mvn -B package' to refresh." >&2
fi

if [[ "${1:-}" == "--list" ]]; then
  exec java -Dspring.shell.interactive.enabled=false -jar "$JAR" 'address list'
fi

if [[ "${1:-}" == "--" ]]; then
  shift
  # AI on whenever the key is available: the option is passed as its own
  # argument so Spring Boot lifts it into the environment as a property
  # (a quoted single-argument command would never activate it).
  AI_ARGS=()
  [[ -n "${MELIOUS_API_KEY:-}" ]] && AI_ARGS=(--neis.ai.enabled=true)
  if [[ -n "${1:-}" && "$1" != -* ]]; then
    # Whole command as ONE argument (the non-interactive runner re-parses
    # it, so nested quoting works for values with spaces).
    exec java -Dspring.shell.interactive.enabled=false -jar "$JAR" "$1" "${AI_ARGS[@]}"
  fi
  exec java -Dspring.shell.interactive.enabled=false -jar "$JAR" "$@" "${AI_ARGS[@]}"
fi

cat >&2 <<'EOF'
Nothing to do. The MVP launcher command (mvp-chain) is retired.

  core/run.sh              # run the configured chain core-chain-ssafp
  core/start.sh --list     # show the registered addresses
  core/start.sh -- '<command>'   # any other CLI command
EOF
exit 1
