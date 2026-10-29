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
# Keep real Java method names in the client so crash reports say where they
# happened (costs a little size). Set EMBER_OBFUSCATE=1 to turn names off again.
if [ "${EMBER_OBFUSCATE:-0}" != "1" ]; then
  sed -i 's/obfuscated: !diagnostic,/obfuscated: false,/' wasm-toolchain/link-web-target-standalone.js
  grep -n 'obfuscated: false' wasm-toolchain/link-web-target-standalone.js
  # The single-file builder refuses a client with names left in; allow it.
  sed -i 's/if (wasm.length > 130_000_000 \&\& !diagnosticBuild)/if (wasm.length > 400_000_000 \&\& !diagnosticBuild)/' wasm-toolchain/build-single-html.js
  grep -n '400_000_000' wasm-toolchain/build-single-html.js
fi
npm ci --ignore-scripts --no-audit --no-fund
free -m
npm run build:single -- --output "$OUT/ember-client-26.2.html"
ls -la "$OUT"
