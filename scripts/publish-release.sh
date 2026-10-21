#!/usr/bin/env bash
# Publishes the GitHub Release of a tag, or removes the drafts a failed run left behind. Used by
# the publish job of .github/workflows/release.yml, with GH_REPO and GH_TOKEN set. Usage:
#
#   scripts/publish-release.sh publish <tag> <title> <notes file> <asset>...
#   scripts/publish-release.sh clean-drafts <tag>
#
# A published Release never changes. publish refuses a tag that already has a Release or a
# draft (a re-run after publishing fails, it does not add a second Release). clean-drafts
# deletes drafts by id, never by tag name: a tag can have a published Release and a draft at
# once, and a lookup by tag name may return either.

set -euo pipefail

fail() {
    echo "publish-release: $*" >&2
    exit 1
}

# "<id> <draft>" for every Release of the tag, drafts included
releases_of() {
    gh api --paginate "repos/$GH_REPO/releases?per_page=100" |
        TAG="$1" jq -r '.[] | select(.tag_name == env.TAG) | "\(.id) \(.draft)"'
}

publish() {
    local tag=$1 title=$2 notes=$3 existing
    shift 3
    existing="$(releases_of "$tag")"
    if [[ -n $existing ]]; then
        fail "$tag already has a Release or a draft: a published Release never changes, delete a draft on GitHub to publish again"
    fi
    # With assets, gh keeps the Release a draft while it uploads them, publishes it at the end,
    # and deletes its own draft when an upload fails
    gh release create "$tag" --verify-tag --title "$title" --notes-file "$notes" "$@"
}

clean_drafts() {
    local id draft
    releases_of "$1" | while read -r id draft; do
        if [[ $draft == true ]]; then
            gh api -X DELETE "repos/$GH_REPO/releases/$id" >/dev/null
            echo "Deleted draft $id of $1"
        fi
    done
}

case "${1-}" in
    publish) [[ $# -ge 5 ]] || fail "usage: $0 publish <tag> <title> <notes file> <asset>..."; shift; publish "$@" ;;
    clean-drafts) [[ $# -eq 2 ]] || fail "usage: $0 clean-drafts <tag>"; clean_drafts "$2" ;;
    *) fail "usage: $0 publish <tag> <title> <notes file> <asset>... | clean-drafts <tag>" ;;
esac
