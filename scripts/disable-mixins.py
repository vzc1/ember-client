#!/usr/bin/env python3
"""Switches off individual mixins inside mod jars (and their nested jars).

Usage: disable-mixins.py <list-file> <mod jar or dir>...
The list holds lines like  fabric-message-api-v1.client.mixins.json:ChatListenerMixin
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
    for key in ("mixins", "client", "server"):
        if isinstance(cfg.get(key), list):
            keep = [m for m in cfg[key] if m not in names]
            removed += [m for m in cfg[key] if m in names]
            cfg[key] = keep
    return json.dumps(cfg, indent=2).encode(), removed

def patch_zip_bytes(data, want, label):
    src = zipfile.ZipFile(io.BytesIO(data))
    out = io.BytesIO()
    changed = False
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as dst:
        for info in src.infolist():
            b = src.read(info.filename)
            base = os.path.basename(info.filename)
            if base in want and "/" not in info.filename.strip("/"):
                b2, removed = patch_config(b, want[base])
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
