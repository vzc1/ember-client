#!/usr/bin/env bash
# Uploads files to a draft (non-public) release so they can be fetched through
# the GitHub API. Replaces any existing draft with the same tag.
# Usage: publish-draft.sh <tag> <title> <file>...
set -euo pipefail
export GH_REPO="$GITHUB_REPOSITORY"
tag="$1"; title="$2"; shift 2
existing="$(gh api "repos/$GITHUB_REPOSITORY/releases?per_page=100" --jq ".[] | select(.draft and .tag_name == \"$tag\") | .id")"
for id in $existing; do gh api -X DELETE "repos/$GITHUB_REPOSITORY/releases/$id"; done
files=()
for f in "$@"; do [ -s "$f" ] && files+=("$f"); done
gh release create "$tag" --draft --title "$title" --notes "Automated build output (draft, not public)." \
  --target "$GITHUB_SHA" "${files[@]}"
