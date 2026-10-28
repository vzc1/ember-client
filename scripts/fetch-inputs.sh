#!/usr/bin/env bash
# Downloads the patcher kit and the official client jar, verifying both.
set -euo pipefail
IN="$RUNNER_TEMP/inputs"
mkdir -p "$IN/kit"

curl --fail --location --retry 3 "$SETUP_JAR_URL" -o "$IN/setup.jar"
echo "$SETUP_JAR_SHA256  $IN/setup.jar" | sha256sum --check

unzip -p "$IN/setup.jar" portable-kit.zip > "$IN/portable-kit.zip"
kit_sha="$(unzip -p "$IN/setup.jar" portable-kit.sha256 | tr -d '[:space:]')"
echo "$kit_sha  $IN/portable-kit.zip" | sha256sum --check
unzip -q -o "$IN/portable-kit.zip" -d "$IN/kit"
(cd "$IN/kit" && sha256sum --check --quiet SHA256SUMS)

curl --fail --location --retry 3 "$CLIENT_JAR_URL" -o "$IN/client-26.2.jar"
echo "$CLIENT_JAR_SHA1  $IN/client-26.2.jar" | sha1sum --check
