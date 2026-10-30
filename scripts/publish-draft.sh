#!/usr/bin/env bash
# Uploads files to a draft (non-public) release so they can be fetched through
# the GitHub API. Replaces any existing draft with the same tag. Each upload is
# retried, since big files sometimes drop mid-transfer.
# Usage: publish-draft.sh <tag> <title> <file>...
set -uo pipefail
export GH_REPO="$GITHUB_REPOSITORY"
tag="$1"; title="$2"; shift 2
existing="$(gh api "repos/$GITHUB_REPOSITORY/releases?per_page=100" --jq ".[] | select(.draft and .tag_name == \"$tag\") | .id")"
for id in $existing; do gh api -X DELETE "repos/$GITHUB_REPOSITORY/releases/$id" || true; done
for i in 1 2 3; do
  gh release create "$tag" --draft --title "$title" --notes "Automated build output (draft, not public)." \
    --target "$GITHUB_SHA" && break
  echo "release create failed (try $i)"; sleep 10
done
rc=0
for f in "$@"; do
  [ -s "$f" ] || continue
  ok=0
  for i in 1 2 3 4; do
    if gh release upload "$tag" "$f" --clobber; then ok=1; break; fi
    echo "upload of $f failed (try $i)"; sleep 20
  done
  [ $ok = 1 ] || rc=1
done
exit $rc
