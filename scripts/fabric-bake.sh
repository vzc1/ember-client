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

# 3. Bake: run Knot with all mods, dump transformed classes, exit before the game starts.
#    A mixin that cannot apply to Eaglercraft's version of a class gets switched off
#    and the bake repeats, so one incompatible mixin never costs a whole class.
DISABLED="$F/disabled-mixins.txt"
grep -vhE '^\s*(#|$)' "$REPO/fabric/disabled-mixins.txt" 2>/dev/null > "$DISABLED" || true
for round in 1 2 3 4 5; do
  rm -rf "$F/run" "$F/baked"
  mkdir -p "$F/run/mods"
  cp "$F"/mods/*.jar "$F/run/mods/" 2>/dev/null || true
  python3 "$REPO/scripts/disable-mixins.py" "$DISABLED" "$F"/run/mods/*.jar
  echo "== bake round $round ($(wc -l < "$DISABLED") mixins switched off)"
  (cd "$F/run" && "$JAVA" -Xmx6g \
    -cp "$FABRIC_CP$GAME_CLASSES$SEP$EMBER_GAME_CP" \
    -Dfabric.addMods="$F/addmods/ember_bake" \
    -Dfabric.gameVersion=26.2 \
    -Dember.bake.out="$F/baked" \
    -Dember.bake.gameClasses="$GAME_CLASSES" \
    net.fabricmc.loader.impl.launch.knot.KnotClient --gameDir "$F/run") > "$F/bake.log" 2>&1 || true
  test -f "$F/baked/bake-report.txt" || { tail -80 "$F/bake.log"; echo "::error::Fabric bake did not finish (see bake.log)"; exit 1; }
  new="$(comm -13 <(sort -u "$DISABLED") <(sort -u "$F/baked/failed-mixins.txt"))"
  [ -z "$new" ] && break
  echo "$new" >> "$DISABLED"
done
cat "$F/baked/bake-report.txt"
echo "== mixins switched off for Eaglercraft compatibility:"; cat "$DISABLED"

# 4. Assemble ember-baked.jar: Fabric runtime classes, baked classes, then the
#    browser-side loader shim + generated entrypoints on top.
ROOT="$F/jar-root"
rm -rf "$ROOT"; mkdir -p "$ROOT"
for lib in "$F"/libs/fabric-loader-*.jar "$F"/libs/sponge-mixin-*.jar "$F"/libs/mixinextras-*.jar "$F"/libs/asm-*.jar; do
  [ -f "$lib" ] && (cd "$ROOT" && unzip -q -o "$lib" -x 'META-INF/*' 'module-info.class' 'LICENSE*' 'fabric.mod.json')
done
# MixinExtras ships inside the loader jar (jar-in-jar).
unzip -p "$F"/libs/fabric-loader-*.jar 'META-INF/jars/mixinextras-fabric-*.jar' > "$F/mixinextras.jar" 2>/dev/null || true
[ -s "$F/mixinextras.jar" ] && (cd "$ROOT" && unzip -q -o "$F/mixinextras.jar" -x 'META-INF/*' 'module-info.class' 'fabric.mod.json')
find "$ROOT" -name module-info.class -delete
cp -a "$F/baked/classes/." "$ROOT/"
# Eaglercraft swaps some game classes for browser versions with the same name
# (platform-teavm, teavm-compat). Never ship a copy that would cover those.
python3 - "$ROOT" "$WS" <<'PY'
import os, sys
root, ws = sys.argv[1], sys.argv[2]
srcs = [os.path.join(ws, d, "src/main/java") for d in ("platform-teavm", "teavm-compat")]
dropped = []
for dirpath, _, files in os.walk(root):
    for f in files:
        if not f.endswith(".class"): continue
        rel = os.path.relpath(os.path.join(dirpath, f), root)
        top = rel[:-6].split("$", 1)[0]
        if any(os.path.exists(os.path.join(s, top + ".java")) for s in srcs):
            os.remove(os.path.join(dirpath, f)); dropped.append(rel)
print(f"left out {len(dropped)} classes that Eaglercraft replaces for the browser")
for d in sorted(dropped)[:40]: print("  -", d)
PY
"$JAVAC" -nowarn -proc:none --release 21 -encoding UTF-8 -d "$ROOT" \
  -cp "$ROOT$SEP$GAME_CLASSES$SEP$EMBER_GAME_CP" \
  $(find "$REPO/fabric/runtime/src" -name '*.java') "$F/baked/gen/net/emberclient/fabric/EmberGenerated.java"
mkdir -p "$WS/ember"
rm -f "$WS/ember/ember-baked.jar"
(cd "$ROOT" && zip -q -r -X "$WS/ember/ember-baked.jar" .)
ls -la "$WS/ember/ember-baked.jar"

# 5. Mod assets go into the game's resource tree so they land in assets.epk.
cp -a --update=none "$F/baked/resources/." "$WS/game/src/main/resources/"

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

# 7. Let the client link finish when the only problems are Java features a mod
#    mentions but the browser lacks: those now fail only if that code runs.
L="$WS/wasm-toolchain/StandaloneTeaVMLinker.java"
if [ -f "$L" ] && ! grep -q EMBER_ALLOW_MISSING "$L"; then
python3 - "$L" <<'PY'
import sys
p = sys.argv[1]; s = open(p).read()
old = """        if (!result.getProblems().getSevereProblems().isEmpty()) {
            throw new IllegalStateException("TeaVM reported severe problems");
        }"""
new = """        var severe = result.getProblems().getSevereProblems();
        int missing = 0, other = 0;
        for (var p : severe) {
            String t = p.getText();
            if (t != null && t.endsWith("was not found")) missing++; else other++;
        }
        boolean allowMissing = "1".equals(System.getenv("EMBER_ALLOW_MISSING"));
        if (other > 0 || (missing > 0 && !allowMissing)) {
            throw new IllegalStateException("TeaVM reported severe problems");
        }
        if (missing > 0) {
            System.out.println("[ember] linked with " + missing + " missing Java features; mods hit them only if that code runs");
        }"""
assert old in s, "linker check not found"
open(p, "w").write(s.replace(old, new))
print("patched StandaloneTeaVMLinker to allow missing Java features in mods")
PY
fi

# 8. TeaVM itself stops before writing output if any reachable code mentions a
#    class/method/field that doesn't exist, even though it already replaces such
#    references with code that throws when reached. Report them as warnings so
#    mods that mention desktop-only Java features still build.
TV="$WS/wasm-toolchain/teavm-eagler-patch/teavm-core-0.13.1-eagler.jar"
if [ -f "$TV" ] && [ ! -f "$TV.ember-patched" ]; then
  mkdir -p "$F/tools"
  ASM_CP="$(ls "$F"/libs/asm-*.jar | tr '\n' "$SEP")"
  "$JAVAC" -nowarn -d "$F/tools" -cp "$ASM_CP" "$REPO/fabric/tools/PatchTeaVM.java"
  "$JAVA" -cp "$F/tools$SEP$ASM_CP" PatchTeaVM "$TV"
  touch "$TV.ember-patched"
fi
