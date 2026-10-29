#!/usr/bin/env bash
# Downloads Fabric Loader + its libraries for 26.2 (from Fabric's meta API)
# and the mods listed in fabric/mods.txt (from Modrinth).
# Usage: fetch-fabric.sh <dest-dir>
set -euo pipefail
DEST="$1"
mkdir -p "$DEST/libs" "$DEST/mods"
MC=26.2

meta="$(curl -fsSL "https://meta.fabricmc.net/v2/versions/loader/$MC")"
loader_version="$(jq -r '[.[] | select(.loader.stable)][0].loader.version // .[0].loader.version' <<<"$meta")"
echo "Fabric Loader $loader_version"
echo "$loader_version" > "$DEST/loader-version.txt"

maven_path() { # group:artifact:version -> group/path/artifact/version/artifact-version.jar
  IFS=: read -r g a v <<<"$1"
  echo "${g//.//}/$a/$v/$a-$v.jar"
}
fetch_lib() { # coords base-url
  local p; p="$(maven_path "$1")"
  local f="$DEST/libs/$(basename "$p")"
  [ -s "$f" ] || curl -fsSL --retry 3 "${2%/}/$p" -o "$f"
  echo "  lib $(basename "$f")"
}

fetch_lib "net.fabricmc:fabric-loader:$loader_version" https://maven.fabricmc.net/
entry="$(jq -c --arg v "$loader_version" '[.[] | select(.loader.version == $v)][0]' <<<"$meta")"
jq -r '.launcherMeta.libraries | (.common // []) + (.client // []) | .[] | "\(.name) \(.url // "https://maven.fabricmc.net/")"' <<<"$entry" |
while read -r coords url; do fetch_lib "$coords" "$url"; done
# intermediary is irrelevant for unobfuscated 26.x but harmless if listed
ls -la "$DEST/libs"

python3 "$(dirname "$0")/fetch-mods.py" "$DEST/mods" fabric/mods.txt mods "$DEST/mods.json"
ls -la "$DEST/mods"
