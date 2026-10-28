#!/usr/bin/env bash
# Decompiles the official jar and applies the 26.2 u2 browser patches.
# The generated source stays in the runner's temp folder and is never committed.
set -euo pipefail
IN="$RUNNER_TEMP/inputs"
K="$IN/kit"
WS="$RUNNER_TEMP/ws"
sha() { sha256sum "$1" | cut -d' ' -f1; }

"$JAVA_HOME_17_X64/bin/java" -jar "$K/eaglercraft-26.2-java-cli.jar" create-dev \
  --jar "$IN/client-26.2.jar" \
  --output "$WS" \
  --vineflower "$K/inputs/vineflower-1.12.0.jar" \
  --java17 "$JAVA_HOME_17_X64/bin/java" \
  --patch-bundle "$K/source-patch-bundle.zip" \
  --expected-bundle-sha256 "$(sha "$K/source-patch-bundle.zip")" \
  --project-skeleton "$K/project-skeleton-v5-teavm-runtime-verified.zip" \
  --expected-skeleton-sha256 "$(sha "$K/project-skeleton-v5-teavm-runtime-verified.zip")" \
  --resource-overlay "$K/inputs/resource-overlay-normal.zip" \
  --expected-resource-overlay-sha256 "$(sha "$K/inputs/resource-overlay-normal.zip")" \
  --external-resource-root "$K/inputs/resources"

test -d "$WS/game/src/main/java"
chmod +x "$WS/gradlew"
