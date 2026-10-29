#!/usr/bin/env bash
# Adds Ember's title-screen splash lines to the game's own list.
set -euo pipefail
WS="$1"
F="$WS/game/src/main/resources/assets/minecraft/texts/splashes.txt"
[ -f "$F" ] || { echo "no splashes.txt; skipping Ember splashes"; exit 0; }
grep -q "Now with Fabric!" "$F" && exit 0
cat >> "$F" <<'TXT'
Now with Fabric!
Ember Client!
Mods in your browser!
Hot off the forge!
Warm and toasty!
Stoke the fire!
Made of embers!
Lava-powered!
No install needed!
Works on a school laptop!
TXT
echo "added Ember splashes"
