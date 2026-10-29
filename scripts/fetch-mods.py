#!/usr/bin/env python3
"""Downloads the Modrinth mods listed in fabric/mods.txt (plus required
dependencies) for Fabric + Minecraft 26.2, copies jars from mods/, drops any
mod whose Fabric dependencies are missing, and writes mods.json for the site.

Usage: fetch-mods.py <dest-mods-dir> <mods.txt> <user-mods-dir> <manifest.json>
"""
import io, json, os, shutil, sys, urllib.parse, urllib.request, zipfile

MC = "26.2"
UA = "vzc1/ember-client (github.com/vzc1/ember-client)"
UNSUPPORTED = {"fabric-language-kotlin": "Kotlin mods are not supported yet"}
BUILTIN = {"minecraft", "java", "fabricloader", "fabric-loader"}

def get(url):
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    with urllib.request.urlopen(req, timeout=60) as r:
        return r.read()

def getj(url):
    return json.loads(get(url))

def best_version(project):
    q = urllib.parse.urlencode({"loaders": '["fabric"]', "game_versions": f'["{MC}"]'})
    versions = getj(f"https://api.modrinth.com/v2/project/{project}/version?{q}")
    for kind in ("release", "beta", "alpha"):
        for v in versions:
            if v.get("version_type") == kind:
                return v
    return versions[0] if versions else None

def author_of(project):
    try:
        members = getj(f"https://api.modrinth.com/v2/project/{project['id']}/members")
        owner = next((m for m in members if m.get("role") == "Owner"), members[0] if members else None)
        if owner:
            return owner["user"]["username"]
    except Exception:
        pass
    return ""

# ---- Fabric metadata from jars -------------------------------------------------

def read_mod_meta(data):
    """Returns list of (id, provides, depends) for a jar and its nested jars."""
    out = []
    try:
        z = zipfile.ZipFile(io.BytesIO(data))
    except zipfile.BadZipFile:
        return out
    if "fabric.mod.json" in z.namelist():
        try:
            m = json.loads(z.read("fabric.mod.json").decode("utf-8", "replace"), strict=False)
            out.append((m.get("id"), set(m.get("provides") or []), set((m.get("depends") or {}).keys())))
        except Exception:
            pass
    for n in z.namelist():
        if n.startswith("META-INF/jars/") and n.endswith(".jar"):
            out += read_mod_meta(z.read(n))
    return out

def main():
    dest, listfile, user_dir, manifest_path = sys.argv[1:5]
    os.makedirs(dest, exist_ok=True)
    wanted = []
    if os.path.exists(listfile):
        for line in open(listfile):
            line = line.split("#", 1)[0].strip()
            if line:
                wanted.append(line)

    files = {}      # filename -> info for the site
    done, queue = set(), [(s, True) for s in wanted]
    while queue:
        slug, listed = queue.pop(0)
        if slug in done:
            continue
        done.add(slug)
        try:
            project = getj(f"https://api.modrinth.com/v2/project/{slug}")
        except Exception as e:
            print(f"::warning::could not find {slug} on Modrinth: {e}")
            continue
        if project["slug"] in UNSUPPORTED:
            print(f"::warning::skipping {project['slug']}: {UNSUPPORTED[project['slug']]}")
            continue
        v = best_version(project["id"])
        if not v:
            print(f"::warning::{project['slug']} has no Fabric build for {MC}")
            continue
        fl = v.get("files") or []
        f = next((x for x in fl if x.get("primary")), fl[0] if fl else None)
        if not f:
            continue
        name = urllib.parse.unquote(f["filename"])
        open(os.path.join(dest, name), "wb").write(get(f["url"]))
        cats = [c for c in project.get("categories", []) if c not in ("fabric", "quilt", "forge", "neoforge")]
        files[name] = {
            "slug": project["slug"], "name": project["title"], "author": author_of(project),
            "description": project.get("description", ""), "icon": project.get("icon_url") or "",
            "categories": cats, "version": v["version_number"],
            "url": f"https://modrinth.com/mod/{project['slug']}", "listed": listed, "source": "modrinth",
        }
        print(f"  modrinth: {project['slug']} {v['version_number']} -> {name}")
        for d in v.get("dependencies") or []:
            if d.get("dependency_type") == "required" and d.get("project_id"):
                queue.append((d["project_id"], False))

    if os.path.isdir(user_dir):
        for n in sorted(os.listdir(user_dir)):
            if n.endswith(".jar"):
                shutil.copy(os.path.join(user_dir, n), os.path.join(dest, n))
                files[n] = {"slug": "", "name": n[:-4], "author": "", "description": "Added from the mods folder.",
                            "icon": "", "categories": [], "version": "", "url": "", "listed": True, "source": "folder"}
                print("  your mod:", n)

    # Drop mods whose Fabric dependencies are missing, until everything left resolves.
    metas = {n: read_mod_meta(open(os.path.join(dest, n), "rb").read()) for n in files}
    while True:
        have = set(BUILTIN)
        for n, ms in metas.items():
            for mid, prov, _ in ms:
                if mid: have.add(mid)
                have |= prov
        bad = {}
        for n, ms in metas.items():
            if not ms:
                bad[n] = "not a Fabric mod"
                continue
            missing = sorted(ms[0][2] - have)
            if missing:
                bad[n] = "needs " + ", ".join(missing)
        if not bad:
            break
        for n, why in bad.items():
            print(f"::warning::leaving out {n}: {why}")
            os.remove(os.path.join(dest, n))
            metas.pop(n); files.pop(n)

    # Fill in names from fabric.mod.json for folder mods.
    for n, info in files.items():
        if info["source"] == "folder" and metas.get(n):
            try:
                z = zipfile.ZipFile(os.path.join(dest, n))
                m = json.loads(z.read("fabric.mod.json").decode("utf-8", "replace"), strict=False)
                info["name"] = m.get("name") or info["name"]
                info["description"] = m.get("description") or info["description"]
                a = m.get("authors") or []
                info["author"] = (a[0]["name"] if a and isinstance(a[0], dict) else (a[0] if a else ""))
                info["version"] = m.get("version", "")
            except Exception:
                pass

    json.dump(sorted(files.values(), key=lambda x: (not x["listed"], x["name"].lower())),
              open(manifest_path, "w"), indent=1)
    print(f"{len(files)} mods ready; manifest -> {manifest_path}")

main()
