#!/usr/bin/env bash
# Releases SafeRec: runs every check, bumps the version, commits, tags and pushes. GitHub
# then builds, signs and publishes the Release from the tag (.github/workflows/release.yml).
# Usage:
#
#   scripts/release.sh <version>   # X.Y or X.Y.Z, greater than the current versionName
#   scripts/release.sh --retag     # after a failed release run that needed a code fix:
#                                  # moves the current version's tag to HEAD, only while
#                                  # GitHub holds no Release (or draft) for it
#
# Nothing is committed or pushed unless every check passes: the same checks as CI, then a
# signed release build and its certificate (the keystore is on this machine, so a signing
# problem shows here, not after the tag is public). Only app/build.gradle.kts is committed.

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
GRADLE=app/build.gradle.kts
ACTIONS=https://github.com/ark3us/saferec/actions

fail() {
    echo "release: $*" >&2
    exit 1
}

check_git_state() {
    git fetch -q --no-tags origin
    [[ $(git symbolic-ref --short -q HEAD) == main ]] || fail "not on main"
    [[ $(git rev-parse HEAD) == "$(git rev-parse origin/main)" ]] || fail "main differs from origin/main"
    git diff --cached --quiet || fail "something is staged"
    git diff --quiet -- "$GRADLE" || fail "$GRADLE has local changes"
}

tag_exists() {
    git rev-parse -q --verify "refs/tags/$1" >/dev/null ||
        git ls-remote --exit-code -q --tags origin "refs/tags/$1" >/dev/null
}

run_checks() {
    bash scripts/ci-checks.sh
    # ci-checks.sh ends on the build without the three presses: this builds the real one
    bash ./gradlew :app:assembleRelease
    bash scripts/check-apk-cert.sh app/build/outputs/apk/release/app-release.apk
}

release() {
    local version="$1" tag="v$1" current code
    [[ $version =~ ^[0-9]+\.[0-9]+(\.[0-9]+)?$ ]] || fail "version must be X.Y or X.Y.Z, got '$version'"
    current="$(bash scripts/app-version.sh)"
    if [[ $version == "$current" || $(printf '%s\n%s\n' "$current" "$version" | sort -V | tail -1) != "$version" ]]; then
        fail "$version is not greater than $current"
    fi
    check_git_state
    if tag_exists "$tag"; then
        fail "tag $tag already exists"
    fi
    run_checks

    code="$(bash scripts/app-version.sh --code)"
    sed -i -e "s/^\( *versionName = \)\".*\"$/\1\"$version\"/" \
        -e "s/^\( *versionCode = \)[0-9]*$/\1$((code + 1))/" "$GRADLE"
    git commit -q -m "Version $version" -- "$GRADLE"
    git tag -a "$tag" -m "SafeRec $version"
    if ! git push -q --atomic origin main "refs/tags/$tag"; then
        # Nothing reached GitHub: undo the commit and the tag, keep every other local change
        git tag -d "$tag" >/dev/null
        git reset -q HEAD~1
        git checkout -q -- "$GRADLE"
        fail "push failed: the version commit and the tag were undone"
    fi
    echo "Pushed $tag. Follow the release run: $ACTIONS"
}

retag() {
    local version tag releases
    version="$(bash scripts/app-version.sh)"
    tag="v$version"
    check_git_state
    tag_exists "$tag" || fail "tag $tag does not exist: release a new version instead"
    # A failing gh must not look like "no Release": the tag of a published Release never moves
    releases="$(gh release list --limit 1000 --json tagName --jq '.[].tagName')" ||
        fail "cannot list the Releases on GitHub"
    if grep -qxF "$tag" <<<"$releases"; then
        fail "GitHub has a Release or a draft for $tag: delete a draft on GitHub first, a published Release never moves"
    fi
    run_checks

    git tag -fa "$tag" -m "SafeRec $version"
    git push -q -f origin "refs/tags/$tag"
    echo "Moved $tag to $(git rev-parse --short HEAD). Follow the release run: $ACTIONS"
}

case "${1-}" in
    --retag) [[ $# -eq 1 ]] || fail "usage: $0 --retag"; retag ;;
    *) [[ $# -eq 1 ]] || fail "usage: $0 <version> | --retag"; release "$1" ;;
esac
