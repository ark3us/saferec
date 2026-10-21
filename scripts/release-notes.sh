#!/usr/bin/env bash
# Prints the notes of a GitHub Release, in Markdown: the commit subjects since the previous
# tag. GitHub's generated notes list merged pull requests, and changes reach main without
# them. Usage:
#
#   scripts/release-notes.sh <tag>

set -euo pipefail

if [[ $# -ne 1 ]]; then
    echo "Usage: $0 <tag>" >&2
    exit 2
fi
TAG="$1"
cd "$(dirname "$0")/.."

if previous="$(git describe --tags --abbrev=0 "$TAG^" 2>/dev/null)"; then
    git log --format='- %s' "$previous..$TAG"
else
    echo "First release distributed from GitHub. See the [README](https://github.com/ark3us/saferec#readme) to install it and check its signature."
fi
