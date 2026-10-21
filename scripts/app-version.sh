#!/usr/bin/env bash
# Prints the app's version as app/build.gradle.kts declares it, the one source of truth
# for release.sh and the release workflow. Usage:
#
#   scripts/app-version.sh          # versionName, e.g. 1.16
#   scripts/app-version.sh --code   # versionCode, e.g. 17

set -euo pipefail

GRADLE="$(cd "$(dirname "$0")/.." && pwd)/app/build.gradle.kts"

case "${1:-}" in
    "") sed -n 's/^ *versionName = "\(.*\)"$/\1/p' "$GRADLE" ;;
    --code) sed -n 's/^ *versionCode = \([0-9]*\)$/\1/p' "$GRADLE" ;;
    *) echo "Usage: $0 [--code]" >&2; exit 2 ;;
esac
