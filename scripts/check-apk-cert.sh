#!/usr/bin/env bash
# Checks that an APK is signed with SafeRec's release key, the one registered for
# net.ark3us.saferec.app on the Android Developer Console. An APK signed with any other
# key would not install on certified phones, and its Drive sign-in would fail.
#
# Used by scripts/release.sh before a tag is pushed, and by the release workflow before
# a Release is published. Usage:
#
#   scripts/check-apk-cert.sh <apk>
#
# Exits 0 when apksigner accepts the APK and its only signer is the release key, 1 otherwise.

set -euo pipefail

# SHA-256 of keystore.jks (alias cert), as apksigner prints it
EXPECTED=0a541d9a8dfc3c06ee9ec231bdd3de307ad9328cfa82aa0ba53ad6b247ec52bc

if [[ $# -ne 1 ]]; then
    echo "Usage: $0 <apk>" >&2
    exit 2
fi
APK="$1"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"

# apksigner from PATH, else from the newest build-tools of the SDK
find_apksigner() {
    if command -v apksigner >/dev/null; then
        command -v apksigner
        return
    fi
    local sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
    if [[ -z $sdk && -f $ROOT/local.properties ]]; then
        sdk="$(sed -n 's/^sdk\.dir=//p' "$ROOT/local.properties")"
    fi
    local tools
    tools="$(ls -d "$sdk"/build-tools/*/ 2>/dev/null | sort -V | tail -1)"
    if [[ -z $tools || ! -f ${tools}apksigner ]]; then
        echo "FAIL: apksigner not found (PATH, ANDROID_HOME, sdk.dir)" >&2
        exit 1
    fi
    echo "${tools}apksigner"
}
APKSIGNER="$(find_apksigner)"

if ! certs="$(bash "$APKSIGNER" verify --print-certs "$APK" 2>&1)"; then
    echo "FAIL: $APK does not verify:"
    echo "$certs"
    exit 1
fi

# "Signer #1 certificate SHA-256 digest: …" up to build-tools 36, "V3.0 Signer: certificate
# SHA-256 digest: …" (one line per scheme) from 37: every distinct digest must be the release key's
digests="$(sed -n 's/^.*certificate SHA-256 digest: \([0-9a-f]*\)$/\1/p' <<<"$certs" | sort -u)"
if [[ $digests != "$EXPECTED" ]]; then
    echo "FAIL: $APK is not signed with the release key alone"
    echo "  expected: $EXPECTED"
    echo "  found:    ${digests:-none}"
    exit 1
fi
echo "OK: $APK is signed with the release key"
