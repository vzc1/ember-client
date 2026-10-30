#!/usr/bin/env python3
"""Removes bundled (jar-in-jar) libraries from mod jars, per fabric/strip-nested.txt.

Lines look like  <mod jar name prefix>:<nested jar name substring>
e.g.  ViaFabricPlus:vialegacy  drops ViaLegacy from ViaFabricPlus.
Used to keep very large mods small enough for the browser compiler.
"""
import io, json, os, sys, zipfile

def main(rules_path, mods_dir):
    if not os.path.exists(rules_path):
        return
    rules = []
    for line in open(rules_path):
        line = line.strip()
        if line and not line.startswith("#") and ":" in line:
            a, b = line.split(":", 1)
            rules.append((a.lower(), b.lower()))
    for name in sorted(os.listdir(mods_dir)):
        if not name.endswith(".jar"):
            continue
        drop = [b for a, b in rules if name.lower().startswith(a)]
        if not drop:
            continue
        path = os.path.join(mods_dir, name)
        src = zipfile.ZipFile(path)
        removed = [i.filename for i in src.infolist()
                   if i.filename.startswith("META-INF/jars/") and any(d in os.path.basename(i.filename).lower() for d in drop)]
        if not removed:
            continue
        out = io.BytesIO()
        with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as dst:
            for info in src.infolist():
                if info.filename in removed:
                    continue
                data = src.read(info.filename)
                if info.filename == "fabric.mod.json":
                    meta = json.loads(data)
                    meta["jars"] = [j for j in meta.get("jars", []) if j.get("file") not in removed]
                    for dep in ("depends", "recommends"):
                        pass
                    data = json.dumps(meta, indent=2).encode()
                dst.writestr(info, data)
        src.close()
        open(path, "wb").write(out.getvalue())
        print(f"  {name}: removed bundled {[os.path.basename(r) for r in removed]}")

main(sys.argv[1], sys.argv[2])
