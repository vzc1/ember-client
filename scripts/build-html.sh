#!/usr/bin/env bash
# Builds the single playable HTML file.
set -euo pipefail
K="$RUNNER_TEMP/inputs/kit"
WS="$RUNNER_TEMP/ws"
OUT="$GITHUB_WORKSPACE/out"
mkdir -p "$OUT"

# The builder reads these from fixed folders; music is required even when
# it is shipped as a separate resource pack.
mkdir -p "$WS/target_teavm_wasm_gc/build/web" "$WS/target_teavm/build/web"
cp "$K/inputs/sounds.epk" "$WS/target_teavm_wasm_gc/build/web/sounds.epk"
cp "$K/inputs/sounds.epk" "$WS/target_teavm/build/web/sounds.epk"
cp "$K/inputs/music.epk"  "$WS/target_teavm/build/web/music.epk"

cd "$WS"
npm ci --ignore-scripts --no-audit --no-fund
free -m
npm run build:single -- --output "$OUT/ember-client-26.2.html"
ls -la "$OUT"
