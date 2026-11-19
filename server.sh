#!/usr/bin/env bash
# NEIS SERVER launcher (US-18.6, issue #121): boots the SERVER run
# mode — web layer on, remote adapters (REST API, MCP) enabled,
# interactive shell off. The CLI launchers (core/start.sh, core/run.sh)
# stay one-shot: their default web-application-type=none means no web
# server ever starts for them.
#
# Usage:
#   server.sh                    # foreground server (Ctrl-C stops it)
#   NEIS_SERVER_PORT=9090 server.sh
#   NEIS_SERVER_BIND=0.0.0.0 server.sh   # WARNING: exposes the
#                                # UNAUTHENTICATED remote adapters
#                                # (US-18.3) to the network — deliberate
#                                # act, know what you are doing.
#
# Security posture: the remote adapters are UNAUTHENTICATED for now
# (owner decision 2026-11-19); the bind defaults to 127.0.0.1 so the
# server is reachable only locally unless explicitly opened.
set -euo pipefail
cd "$(dirname "$0")"

# JDK 21 (the host default java is 17; the project targets class-file
# 65). A pre-existing JAVA_HOME is honored only if it really is 21+.
JAVA_HOME_CANDIDATE="${JAVA_HOME:-}"
JAVA_HOME_DEFAULT=/usr/lib/jvm/java-21-openjdk-amd64
java_major() { "$1" -version 2>&1 | sed -n 's/.*version "\([0-9]*\).*/\1/p'; }
if [[ -z "$JAVA_HOME_CANDIDATE" ]] || [[ "$(java_major "$JAVA_HOME_CANDIDATE/bin/java" 2>/dev/null || echo 0)" -lt 21 ]]; then
  JAVA_HOME_CANDIDATE="$JAVA_HOME_DEFAULT"
fi
export JAVA_HOME="$JAVA_HOME_CANDIDATE"
export PATH="$JAVA_HOME/bin:$PATH"

# Build once if the jar is missing
JAR="$(ls -t target/neis-*.jar 2>/dev/null | grep -v original | head -1 || true)"
if [[ -z "$JAR" ]]; then
  echo "No packaged jar found - building..."
  mvn -B -q -DskipTests package
  JAR="$(ls -t target/neis-*.jar | grep -v original | head -1)"
fi

# Warn when sources are newer than the packaged jar (stale-jar guard).
if [[ -n "$(find src pom.xml -newer "$JAR" -print -quit 2>/dev/null)" ]]; then
  echo "NOTE: sources changed since the jar was built - run 'mvn -B package' to refresh." >&2
fi

# Server-mode property pair: the gate AND the web type must be set
# TOGETHER (ServerModeConfig fails the boot when only one is set).
# ServerModeConfig also overrides Spring Shell's boot command runner
# with a no-op (server mode serves HTTP; it never executes commands).
exec java -jar "$JAR" \
  --spring.main.web-application-type=servlet \
  --neis.server.enabled=true \
  --spring.shell.interactive.enabled=false \
  --neis.server.bind="${NEIS_SERVER_BIND:-127.0.0.1}" \
  --neis.server.port="${NEIS_SERVER_PORT:-8080}" \
  "$@"
