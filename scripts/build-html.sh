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
# If the compiler stalls, record what it is doing (thread dumps go to the ci-logs
# branch) and stop after 150 minutes instead of running into the job limit.
(
  sleep 1500
  for i in 1 2 3 4 5 6 7 8 9 10; do
    for pid in $(pgrep -f StandaloneTeaVMLinker); do
      "$JAVA_HOME/bin/jcmd" "$pid" Thread.print > "$RUNNER_TEMP/logs/stall-$i-$pid.log" 2>&1 || true
      "$JAVA_HOME/bin/jcmd" "$pid" GC.heap_info >> "$RUNNER_TEMP/logs/stall-$i-$pid.log" 2>&1 || true
    done
    sleep 300
  done
) &
WATCH=$!
timeout 150m npm run build:single -- --output "$OUT/ember-client-26.2.html"
kill $WATCH 2>/dev/null || true
ls -la "$OUT"
