#!/usr/bin/env bash
# NEIS systemd installer — installs, upgrades and removes the packaged
# NEIS server (REST + MCP) as a USER-LEVEL systemd service, matching the
# host convention used by hermes-gateway / hermes-dashboard (units in
# ~/.config/systemd/user/, no root needed).
#
#   systemd/install.sh install  [--port 8099] [--no-start] [--fresh-data]
#                               [--allow-stale] [--reset-env]
#   systemd/install.sh upgrade  [--allow-stale] [--no-start] [--reset-env]
#   systemd/install.sh uninstall [--purge]
#   systemd/install.sh status
#
# Maven entry points (profile `dist`, see pom.xml):
#   mvn -Pdist package                    generate the distribution
#                                         package target/neis-dist-<v>.tar.gz
#   mvn -Pdist exec:exec@neis-install     install (stop-if-running, deploy, start)
#   mvn -Pdist exec:exec@neis-upgrade     upgrade (stop-if-running, deploy,
#                                         restart only if it was running)
#   mvn -Pdist exec:exec@neis-uninstall   remove the service (data kept)
#   mvn -Pdist exec:exec@neis-status      service + health status
#
# The distribution package is SELF-INSTALLABLE: untar it anywhere and
# run ./systemd/install.sh install — it needs no repository checkout.
#
# Install layout:
#   ~/.local/share/neis/
#     lib/neis-*.jar         packaged fat jar (copied from the repo build)
#     core/                  the Core v1.0 kit (run.sh/show.sh/start.sh,
#                            seed/, data/, results/) — runnable in place
#     server.sh, start.sh    launchers, copied unmodified
#     neis.env               generated on FIRST install: bind, port, paths
#   ~/.config/systemd/user/neis.service
#
# Data safety: core/data (SQLite store, artifacts, selectors, logs) is
# NEVER touched by install/upgrade — pass --fresh-data to reset it on a
# deliberate fresh install. neis.env is user-editable state: generated on
# first install only, kept across reinstalls and upgrades (--reset-env
# regenerates it). `uninstall` removes the service and binaries but
# KEEPS core/ (pass --purge to delete it too).
#
# Upgrade semantics: stops the service if it is running, deploys the new
# jar and launcher/kit scripts, restarts the service only if it WAS
# running (a stopped service stays stopped), then health-checks.
set -euo pipefail

REPO_DIR="$(cd "$(dirname "$0")/.." && pwd)"
INSTALL_DIR="${HOME}/.local/share/neis"
UNIT_SRC="${REPO_DIR}/systemd/neis.service"
UNIT_DST="${HOME}/.config/systemd/user/neis.service"
SERVICE_NAME="neis"
PORT="8099"
BIND="127.0.0.1"
START="1"
FRESH_DATA="0"
ALLOW_STALE="0"
RESET_ENV="0"
RESET_ENV_REQUESTED="0"

die() { echo "ERROR: $*" >&2; exit 1; }
info() { echo "== $*"; }

fresh_jar() {
  local jar
  # The repository build keeps the jar in target/ (Maven's own dir); a
  # distribution package puts it in lib/ — prefer whichever exists.
  jar="$(ls -t "${REPO_DIR}"/lib/neis-*.jar "${REPO_DIR}"/target/neis-*.jar 2>/dev/null | grep -v original | head -1 || true)"
  [[ -n "$jar" ]] || die "No packaged jar in ${REPO_DIR}/target — run 'mvn -B package' first."
  if [[ "$ALLOW_STALE" == "1" ]]; then
    info "NOTE: sources are newer than the jar (--allow-stale) — installing it as built." >&2
  elif [[ -n "$(find "${REPO_DIR}/src" "${REPO_DIR}/pom.xml" -newer "$jar" -print -quit 2>/dev/null)" ]]; then
    die "Sources are newer than ${jar} — rebuild with 'mvn -B package', or pass --allow-stale to install it as built."
  fi
  echo "$jar"
}

require_installation() {
  [[ -d "$INSTALL_DIR" ]] || die "No NEIS installation at ${INSTALL_DIR} — run 'install' first."
}

service_active() {
  [[ "$(systemctl --user is-active "${SERVICE_NAME}" 2>/dev/null || true)" == "active" ]]
}

# Deploy jar + launchers + core kit. Never touches core/data (the
# store) unless FRESH_DATA=1 on a deliberate reset.
deploy_binaries() {
  local jar="$1"

  mkdir -p "${INSTALL_DIR}/lib"
  info "Installing jar $(basename "$jar") -> ${INSTALL_DIR}/lib/"
  cp -f "$jar" "${INSTALL_DIR}/lib/"

  info "Installing core kit -> ${INSTALL_DIR}/core/"
  if [[ -d "${INSTALL_DIR}/core/data" || -d "${INSTALL_DIR}/core/work" ]] && [[ "$FRESH_DATA" != "1" ]]; then
    # Existing store: copy scripts/seed but leave the store untouched.
    mkdir -p "${INSTALL_DIR}/core/data"
    for item in run.sh show.sh start.sh seed results; do
      [[ -e "${REPO_DIR}/core/$item" ]] && cp -a "${REPO_DIR}/core/$item" "${INSTALL_DIR}/core/"
    done
    cp -an "${REPO_DIR}"/core/work/* "${INSTALL_DIR}/core/data/" 2>/dev/null || true
  else
    rm -rf "${INSTALL_DIR}/core"
    cp -a "${REPO_DIR}/core" "${INSTALL_DIR}/core"
    # Installed layout names the runtime store data/ (repo keeps work/).
    rm -rf "${INSTALL_DIR}/core/data"
    mv "${INSTALL_DIR}/core/work" "${INSTALL_DIR}/core/data"
  fi

  cp -f "${REPO_DIR}/server.sh" "${REPO_DIR}/start.sh" "${INSTALL_DIR}/"
  chmod +x "${INSTALL_DIR}/server.sh" "${INSTALL_DIR}"/core/*.sh
}

# neis.env is user-editable state: create on first install only.
ensure_env() {
  if [[ -f "${INSTALL_DIR}/neis.env" && "$RESET_ENV" != "1" ]]; then
    info "Keeping existing ${INSTALL_DIR}/neis.env (--reset-env regenerates it)"
    return
  fi
  info "Writing ${INSTALL_DIR}/neis.env (port ${PORT}, bind ${BIND})"
  cat > "${INSTALL_DIR}/neis.env" <<EOF
# Generated by systemd/install.sh — edit and 'systemctl --user restart neis'.
NEIS_SERVER_BIND=${BIND}
NEIS_SERVER_PORT=${PORT}
NEIS_DB_PATH=${INSTALL_DIR}/core/data/neis.db
NEIS_CORE_WORK_DIR=${INSTALL_DIR}/core/data
NEIS_LOG_PATH=${INSTALL_DIR}/core/data/logs/neis-server.log
EOF
}

install_unit() {
  mkdir -p "$(dirname "$UNIT_DST")"
  cp -f "$UNIT_SRC" "$UNIT_DST"
  # Boot-start needs a lingering user manager (idempotent).
  loginctl enable-linger "${USER}" 2>/dev/null || \
    info "NOTE: could not enable linger — the service starts at boot only while logged in."
  systemctl --user daemon-reload
}

wait_healthy() {
  local port base
  port="$(grep '^NEIS_SERVER_PORT=' "${INSTALL_DIR}/neis.env" | cut -d= -f2)"
  base="http://127.0.0.1:${port}"
  info "Waiting for ${base}/api/system ..."
  for _ in $(seq 1 60); do
    if curl -sf -o /dev/null "$base/api/system"; then
      info "NEIS is UP at ${base} (MCP endpoint: ${base}/mcp)"
      return 0
    fi
    sleep 1
  done
  die "Service did not become healthy in 60s — journalctl --user -u ${SERVICE_NAME} -e"
}

cmd_install() {
  local jar
  jar="$(fresh_jar)"

  # Re-installing over a running service is an implicit upgrade: stop
  # first so files are not replaced mid-run, then start again after.
  local was_active=0
  if service_active; then
    was_active=1
    info "Service is running — stopping before replacing files"
    systemctl --user stop "${SERVICE_NAME}"
  fi

  deploy_binaries "$jar"
  ensure_env
  install_unit

  if [[ "$START" == "1" || "$was_active" == "1" ]]; then
    systemctl --user enable --now "${SERVICE_NAME}"
  else
    systemctl --user enable "${SERVICE_NAME}"
  fi
  wait_healthy
  cmd_status
}

cmd_upgrade() {
  local jar
  jar="$(fresh_jar)"
  require_installation

  local was_active=0
  if service_active; then
    was_active=1
    info "Service is running — stopping for the upgrade"
    systemctl --user stop "${SERVICE_NAME}"
  else
    info "Service is not running — upgrading files in place"
  fi

  deploy_binaries "$jar"
  ensure_env
  install_unit

  if [[ "$was_active" == "1" && "$START" == "1" ]]; then
    systemctl --user start "${SERVICE_NAME}"
    wait_healthy
  else
    info "Service left stopped — 'systemctl --user start ${SERVICE_NAME}' when ready"
  fi
  cmd_status
}

cmd_uninstall() {
  systemctl --user disable --now "${SERVICE_NAME}" 2>/dev/null || true
  rm -f "$UNIT_DST"
  systemctl --user daemon-reload
  if [[ "${PURGE:-0}" == "1" ]]; then
    info "Removing ${INSTALL_DIR} entirely (--purge)"
    rm -rf "$INSTALL_DIR"
  else
    info "Removing binaries, keeping data in ${INSTALL_DIR}/core"
    rm -rf "${INSTALL_DIR}/lib" "${INSTALL_DIR}/target" \
           "${INSTALL_DIR}/server.sh" "${INSTALL_DIR}/start.sh" "${INSTALL_DIR}/neis.env"
  fi
  info "Uninstalled."
}

cmd_status() {
  systemctl --user --no-pager -l status "${SERVICE_NAME}" || true
  local port
  port="$(grep '^NEIS_SERVER_PORT=' "${INSTALL_DIR}/neis.env" 2>/dev/null | cut -d= -f2 || echo "${PORT}")"
  if curl -sf -o /dev/null "http://127.0.0.1:${port}/api/system"; then
    info "HEALTHY: http://127.0.0.1:${port} (REST /api/*, MCP /mcp)"
  else
    info "NOT RESPONDING on port ${port}"
    return 1
  fi
}

case "${1:-}" in
  install)
    shift
    while [[ $# -gt 0 ]]; do
      case "$1" in
        --port) PORT="$2"; shift 2 ;;
        --no-start) START="0"; shift ;;
        --fresh-data) FRESH_DATA="1"; shift ;;
        --allow-stale) ALLOW_STALE="1"; shift ;;
        --reset-env) RESET_ENV="1"; RESET_ENV_REQUESTED="1"; shift ;;
        *) die "Unknown option: $1" ;;
      esac
    done
    cmd_install ;;
  upgrade)
    shift
    while [[ $# -gt 0 ]]; do
      case "$1" in
        --allow-stale) ALLOW_STALE="1"; shift ;;
        --no-start) START="0"; shift ;;
        --reset-env) RESET_ENV="1"; RESET_ENV_REQUESTED="1"; shift ;;
        *) die "Unknown option: $1" ;;
      esac
    done
    cmd_upgrade ;;
  uninstall)
    shift
    while [[ $# -gt 0 ]]; do
      case "$1" in
        --purge) PURGE="1"; shift ;;
        *) die "Unknown option: $1" ;;
      esac
    done
    cmd_uninstall ;;
  status) cmd_status ;;
  *) sed -n '2,44p' "$0" | sed 's/^# \{0,1\}//'; exit 1 ;;
esac
