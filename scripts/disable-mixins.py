#!/usr/bin/env python3
"""Switches off individual mixins inside mod jars (and their nested jars).

Usage: disable-mixins.py <list-file> <mod jar or dir>...
The list holds lines like  fabric-message-api-v1.client.mixins.json:ChatListenerMixin
("config.json:*" switches off every mixin in that config).
"*:full.class.Name" switches off that mixin class in whichever config lists it.
"@modid:net.minecraft.SomeClass" switches off every mixin of that mod whose class
file mentions SomeClass (use it when the mixin's own name isn't known).
Each matching entry is removed from that mixin config's mixins/client/server arrays.
"""
import io, json, os, sys, zipfile

def load_list(path):
    want = {}
    if os.path.exists(path):
        for line in open(path):
            line = line.strip()
            if line and not line.startswith("#") and ":" in line:
                cfg, name = line.split(":", 1)
                want.setdefault(cfg, set()).add(name)
    return want

def patch_config(data, names):
    cfg = json.loads(data)
    removed = []
    everything = "*" in names
    pkg = cfg.get("package", "") if isinstance(cfg, dict) else ""
    names = set(names) | {n[len(pkg) + 1:] for n in names if pkg and n.startswith(pkg + ".")}
    for key in ("mixins", "client", "server"):
        if isinstance(cfg.get(key), list):
            keep = [m for m in cfg[key] if not everything and m not in names]
            removed += [m for m in cfg[key] if everything or m in names]
            cfg[key] = keep
    return json.dumps(cfg, indent=2).encode(), removed

def target_rules(src, want):
    """Turns "@modid:target.Class" rules into concrete "*:mixin.Class" names for this jar."""
    rules = {k[1:]: v for k, v in want.items() if k.startswith("@")}
    if not rules:
        return set()
    try:
        mod_id = json.loads(src.read("fabric.mod.json")).get("id")
    except Exception:
        return set()
    targets = rules.get(mod_id)
    if not targets:
        return set()
    found = set()
    files = set(src.namelist())
    for name in files:
        # Mixin configs can sit in subfolders (e.g. mixins/common/x.mixins.json).
        if not name.endswith(".json") or name.startswith("assets/") or name.startswith("data/"):
            continue
        try:
            cfg = json.loads(src.read(name))
        except Exception:
            continue
        if not isinstance(cfg, dict) or "package" not in cfg:
            continue
        pkg = cfg["package"]
        for key in ("mixins", "client", "server"):
            for m in cfg.get(key) or []:
                path = (pkg + "." + m).replace(".", "/") + ".class"
                if path not in files:
                    continue
                body = src.read(path)
                if any(("L" + t.replace(".", "/") + ";").encode() in body for t in targets):
                    found.add(pkg + "." + m)
    return found

def patch_zip_bytes(data, want, label):
    src = zipfile.ZipFile(io.BytesIO(data))
    out = io.BytesIO()
    changed = False
    extra = target_rules(src, want)
    if extra:
        print(f"  {label}: mixins matched by target -> {sorted(extra)}")
        want = dict(want)
        want["*"] = set(want.get("*", ())) | extra
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as dst:
        for info in src.infolist():
            b = src.read(info.filename)
            base = os.path.basename(info.filename)
            top = "/" not in info.filename.strip("/")
            names = set(want.get(base, ())) | (set(want.get("*", ())) if base.endswith(".json") else set())
            if names and top:
                try:
                    b2, removed = patch_config(b, names)
                except Exception:
                    removed = []
                if removed:
                    print(f"  {label}: {base} -> disabled {removed}")
                    b, changed = b2, True
            elif info.filename.startswith("META-INF/jars/") and info.filename.endswith(".jar"):
                b2 = patch_zip_bytes(b, want, label + "!" + base)
                if b2 is not None:
                    b, changed = b2, True
            dst.writestr(info, b)
    return out.getvalue() if changed else None

def main():
    want = load_list(sys.argv[1])
    if not want:
        return
    for target in sys.argv[2:]:
        if os.path.isdir(target):
            for cfg_name, names in want.items():
                p = os.path.join(target, cfg_name)
                if os.path.exists(p):
                    b, removed = patch_config(open(p, "rb").read(), names)
                    if removed:
                        open(p, "wb").write(b)
                        print(f"  {target}: {cfg_name} -> disabled {removed}")
        elif target.endswith(".jar"):
            data = open(target, "rb").read()
            new = patch_zip_bytes(data, want, os.path.basename(target))
            if new is not None:
                open(target, "wb").write(new)

main()
