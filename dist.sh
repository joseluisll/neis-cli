#!/usr/bin/env bash
# Distribution build wrapper (US-18.6 dist tooling follow-up): the ONE
# supported way to run the dist goals. Before every invocation it
# checks whether TRACKED sources changed since the last dist build and,
# if so, increments the MINOR part of the single version property
# (neis.version in pom.xml — 0.9 -> 0.10 -> ...), commits the bump, and
# then runs the goal — so the built jar and the neis-dist-<version>
# package always carry the incremented version.
#
# Usage:
#   ./dist.sh                          # bump-if-changed + mvn -Pdist package
#   ./dist.sh exec:exec@neis-install   # bump-if-changed + package + install
#   ./dist.sh exec:exec@neis-upgrade   # bump-if-changed + package + upgrade
#   ./dist.sh exec:exec@neis-uninstall # forward (no rebuild: uninstall)
#   ./dist.sh exec:exec@neis-status    # forward (no rebuild: read-only)
#
# Change detection: a fingerprint of the committed state (HEAD) plus the
# TRACKED working-tree changes (untracked scratch files never bump a
# version) stored in .dist-state at the end of each successful dist
# build. The version-bump commit itself is part of the recorded state,
# so an immediate re-run repackages the SAME version instead of
# double-bumping.
#
# Guards: pom.xml must be committed before a bump (the auto-bump commits
# the version line; uncommitted pom edits would be swallowed into it).
set -euo pipefail
cd "$(dirname "$0")"

MARKER=.dist-state

fingerprint() {
  echo "HEAD=$(git rev-parse HEAD)"
  git status --porcelain --untracked-files=no | sort \
    | shasum | cut -d' ' -f1
}

# ---- pick the goal -----------------------------------------------------
goal="package"
rebuild="yes"
if [[ $# -gt 0 ]]; then
  case "$1" in
    package)                   goal="package" ;;
    exec:exec@neis-install)    goal="$1"; rebuild="yes" ;;
    exec:exec@neis-upgrade)    goal="$1"; rebuild="yes" ;;
    exec:exec@neis-uninstall)  goal="$1"; rebuild="no" ;;
    exec:exec@neis-status)     goal="$1"; rebuild="no" ;;
    *) echo "usage: ./dist.sh [package | exec:exec@neis-install|" >&2
       echo "             exec:exec@neis-upgrade | exec:exec@neis-uninstall |" >&2
       echo "             exec:exec@neis-status]" >&2
       exit 1 ;;
  esac
  shift
fi

# ---- bump the minor version when the tracked state changed -------------
current="$(fingerprint)"
last="$(cat "$MARKER" 2>/dev/null || true)"

if [[ "$current" != "$last" && "$rebuild" == "yes" ]]; then
  if [[ -n "$(git status --porcelain -- pom.xml)" ]]; then
    echo "REFUSING: pom.xml has uncommitted changes — commit them first." >&2
    echo "(the auto-bump commits the version line; uncommitted pom edits" >&2
    echo " would be swallowed into that commit)" >&2
    exit 1
  fi
  version="$(grep -o '<neis\.version>[0-9.]*</neis\.version>' pom.xml \
              | head -1 | sed 's/<neis\.version>//; s/<.*//')"
  major="${version%.*}"
  minor="${version#*.}"
  new="$major.$((minor + 1))"
  sed -i "s|<neis\.version>$version</neis\.version>|<neis\.version>$new</neis\.version>|" pom.xml
  git add pom.xml
  git commit -q -m "Dist version $new (auto-bump: tracked changes since the last dist build)" -- pom.xml
  echo "VERSION $version -> $new (committed)"
fi

# ---- run the goal ------------------------------------------------------
mvn_args="-Pdist package"
if [[ "$rebuild" == "no" ]]; then
  mvn_args="-Pdist"
fi
if [[ "$goal" != "package" ]]; then
  mvn_args="$mvn_args $goal"
fi
# shellcheck disable=SC2086
mvn -B $mvn_args "$@"

# ---- record the state ONLY on success ----------------------------------
fingerprint > "$MARKER"
echo "dist state recorded in $MARKER"
