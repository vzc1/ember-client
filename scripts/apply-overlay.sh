#!/usr/bin/env bash
# Copies Ember's own files (Fabric support, branding, fixes) over the
# generated project. Empty until the Fabric layer lands.
set -euo pipefail
WS="$RUNNER_TEMP/ws"
if [ -d overlay ]; then
  cp -a overlay/. "$WS/"
  echo "Overlay applied:"; (cd overlay && find . -type f | sort)
else
  echo "No overlay yet."
fi
if [ -x scripts/post-overlay.sh ]; then scripts/post-overlay.sh "$WS"; fi
