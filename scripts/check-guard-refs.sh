#!/usr/bin/env bash
# Checks that the build without the three power-key presses never reaches the guard.
#
# With -Psaferec.powerGesture=false the :guard module is compileOnly: its classes
# (net.ark3us.saferec.guard.*) are not packaged, and an app class that references
# one can crash with NoClassDefFoundError. javac drops the code under
# `if (BuildConfig.POWER_GESTURE)`, but two things leak out of it:
#   - a lambda's body, compiled even inside the dead block;
#   - enum switch maps: one synthetic class (Outer$1) builds the maps of every
#     enum switch of its class, so any enum switch there loads Guard$Status.
# Only ui/GuardSetup (called only under the check) may reference the guard.
#
# It compiles the app's debug classes again with the switch off, into the usual
# build directory: the next normal build compiles them again. Usage:
#
#   scripts/check-guard-refs.sh
#
# Exits 0 when no other class references the guard, 1 listing them otherwise.

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CLASSES="$ROOT/app/build/intermediates/javac/debug/compileDebugJavaWithJavac/classes"
GUARD='net/ark3us/saferec/guard'

cd "$ROOT"
# --rerun: a full compile, so no class left by an earlier build is checked
bash ./gradlew -q :app:compileDebugJavaWithJavac --rerun -Psaferec.powerGesture=false

# The classes must come from the switch-off build, or the check means nothing
if ! javap -constants -cp "$CLASSES" net.ark3us.saferec.BuildConfig | grep -q 'POWER_GESTURE = false'; then
    echo "FAIL: $CLASSES is not the build with the switch off"
    exit 1
fi

offenders=0
while IFS= read -r -d '' class; do
    rel="${class#"$CLASSES"/}"
    [[ $rel == net/ark3us/saferec/ui/GuardSetup.class || $rel == net/ark3us/saferec/ui/GuardSetup\$*.class ]] && continue
    # Class names and descriptors sit in the constant pool as plain text
    if grep -qaF "$GUARD" "$class"; then
        echo "FAIL $rel references $(grep -aoE "$GUARD/[A-Za-z0-9_\$]+" "$class" | sort -u | tr '\n' ' ')"
        offenders=$((offenders + 1))
    fi
done < <(find "$CLASSES/net/ark3us/saferec" -name '*.class' -print0 | sort -z)

if ((offenders > 0)); then
    echo "$offenders class(es) reference the guard outside ui/GuardSetup: move that code there"
    exit 1
fi
echo "OK: only ui/GuardSetup references the guard"
