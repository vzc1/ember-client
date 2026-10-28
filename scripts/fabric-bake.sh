#!/usr/bin/env bash
# Ember Fabric layer.
#
# Runs real Fabric Loader once on a desktop JVM, with every mod installed, and
# saves the result: game classes with all mixins applied, mod classes, mod
# assets, and generated Java for the mod list + entrypoints. That bundle is
# packed into ember/ember-baked.jar, which the browser build puts in front of
# the vanilla game classes. The browser never loads mods itself.
#
# Usage: fabric-bake.sh <workspace> <work-dir>
#   <work-dir>/libs  Fabric Loader + libraries   (scripts/fetch-fabric.sh)
#   <work-dir>/mods  mod jars                    (scripts/fetch-fabric.sh)
set -euo pipefail
WS="$(cd "$1" && pwd)"
F="$(mkdir -p "$2" && cd "$2" && pwd)"
REPO="$(cd "$(dirname "$0")/.." && pwd)"
JAVA="${JAVA_HOME:?}/bin/java"
JAVAC="${JAVA_HOME:?}/bin/javac"
SEP=":"

# 1. Compile the game and get its runtime classpath.
if [ -z "${EMBER_GAME_CP:-}" ]; then
  (cd "$WS" && ./gradlew :game:compileJava :game:processResources --console=plain --no-daemon \
      --init-script "$REPO/scripts/print-game-classpath.gradle" emberPrintClasspath) | tee "$F/gradle-cp.log"
  EMBER_GAME_CP="$(grep '^EMBER_GAME_CP=' "$F/gradle-cp.log" | head -1 | cut -d= -f2-)"
fi
GAME_CLASSES="${EMBER_GAME_CLASSES:-$WS/game/build/classes/java/main}"
test -d "$GAME_CLASSES"
FABRIC_CP="$(ls "$F"/libs/*.jar | tr '\n' "$SEP")"

# 2. Compile Ember's own mod and the bake step.
compile_mod() { # <name> <out>
  local src="$REPO/fabric/$1"
  rm -rf "$2"; mkdir -p "$2"
  "$JAVAC" -nowarn -proc:none --release 21 -encoding UTF-8 -d "$2" \
    -cp "$GAME_CLASSES$SEP$FABRIC_CP$EMBER_GAME_CP" \
    $(find "$src/src" -name '*.java')
  if [ -d "$src/resources" ]; then cp -a "$src/resources/." "$2/"; fi
  if [ -f "$src/fabric.mod.json" ]; then cp "$src/fabric.mod.json" "$2/"; fi
}
compile_mod bake "$F/addmods/ember_bake"
compile_mod ember-core "$F/addmods/ember_core"

# 3. Bake: run Knot with all mods, dump transformed classes, exit before the game starts.
rm -rf "$F/run" "$F/baked"
mkdir -p "$F/run/mods"
cp "$F"/mods/*.jar "$F/run/mods/" 2>/dev/null || true
(cd "$F/run" && "$JAVA" -Xmx6g \
  -cp "$FABRIC_CP$GAME_CLASSES$SEP$EMBER_GAME_CP" \
  -Dfabric.addMods="$F/addmods/ember_bake$SEP$F/addmods/ember_core" \
  -Dfabric.gameVersion=26.2 \
  -Dember.bake.out="$F/baked" \
  -Dember.bake.gameClasses="$GAME_CLASSES" \
  net.fabricmc.loader.impl.launch.knot.KnotClient --gameDir "$F/run") 2>&1 | tee "$F/bake.log"
test -f "$F/baked/bake-report.txt" || { echo "::error::Fabric bake did not finish (see bake.log)"; exit 1; }
cat "$F/baked/bake-report.txt"

# 4. Assemble ember-baked.jar: Fabric runtime classes, baked classes, then the
#    browser-side loader shim + generated entrypoints on top.
ROOT="$F/jar-root"
rm -rf "$ROOT"; mkdir -p "$ROOT"
for lib in "$F"/libs/fabric-loader-*.jar "$F"/libs/sponge-mixin-*.jar "$F"/libs/mixinextras-*.jar; do
  [ -f "$lib" ] && (cd "$ROOT" && unzip -q -o "$lib" -x 'META-INF/*' '*.json' 'LICENSE*')
done
cp -a "$F/baked/classes/." "$ROOT/"
"$JAVAC" -nowarn -proc:none --release 21 -encoding UTF-8 -d "$ROOT" \
  -cp "$ROOT$SEP$GAME_CLASSES$SEP$EMBER_GAME_CP" \
  $(find "$REPO/fabric/runtime/src" -name '*.java') "$F/baked/gen/net/emberclient/fabric/EmberGenerated.java"
mkdir -p "$WS/ember"
rm -f "$WS/ember/ember-baked.jar"
(cd "$ROOT" && zip -q -r -X "$WS/ember/ember-baked.jar" .)
ls -la "$WS/ember/ember-baked.jar"

# 5. Mod assets go into the game's resource tree so they land in assets.epk.
cp -a -n "$F/baked/resources/." "$WS/game/src/main/resources/"

# 6. Put ember-baked.jar in front of the vanilla game classes for the client.
for target in target_teavm_wasm_gc; do
  f="$WS/$target/build.gradle.kts"
  grep -q 'ember-baked.jar' "$f" || python3 - "$f" <<'PY'
import re, sys
p = sys.argv[1]; s = open(p).read()
line = '\timplementation(files(rootProject.file("ember/ember-baked.jar"))) // Ember Fabric layer: wins over :game\n'
s, n = re.subn(r'(\ndependencies \{\n)', r'\1' + line.replace('\\', '\\\\'), s, count=1)
assert n == 1, "dependencies block not found in " + p
open(p, 'w').write(s)
PY
  grep -n 'ember-baked' "$f"
done
