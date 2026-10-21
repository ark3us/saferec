#!/usr/bin/env bash
# Every check a commit must pass, the same on GitHub (ci.yml, release.yml) and before a
# release (scripts/release.sh): unit tests and lint, the build without the three presses
# (kept in standby), and the Python tests of scripts/. Usage:
#
#   scripts/ci-checks.sh
#
# Stops at the first failure. Needs a JDK, the Android SDK, uv and openssl.

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

bash ./gradlew testDebugUnitTest lintDebug
bash scripts/check-guard-refs.sh
mkdir -p tmp
uv run --with pytest --with certifi pytest scripts -q --basetemp=tmp/pytest
