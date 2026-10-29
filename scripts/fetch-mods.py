#!/usr/bin/env python3
"""Downloads the Modrinth mods listed in fabric/mods.txt, plus their required
dependencies, for Fabric + Minecraft 26.2. Also copies jars from mods/.

Usage: fetch-mods.py <dest-mods-dir> <mods.txt> <user-mods-dir>
"""
import json, os, shutil, sys, urllib.parse, urllib.request

MC = "26.2"
UA = "vzc1/ember-client (github.com/vzc1/ember-client)"
UNSUPPORTED = {"fabric-language-kotlin": "Kotlin mods are not supported yet"}

def get(url):
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    with urllib.request.urlopen(req, timeout=60) as r:
        return r.read()

def best_version(project):
    q = urllib.parse.urlencode({"loaders": '["fabric"]', "game_versions": f'["{MC}"]'})
    versions = json.loads(get(f"https://api.modrinth.com/v2/project/{project}/version?{q}"))
    if not versions:
        return None
    for kind in ("release", "beta", "alpha"):
        for v in versions:
            if v.get("version_type") == kind:
                return v
    return versions[0]

def main():
    dest, listfile, user_dir = sys.argv[1], sys.argv[2], sys.argv[3]
    os.makedirs(dest, exist_ok=True)
    wanted = []
    if os.path.exists(listfile):
        for line in open(listfile):
            line = line.split("#", 1)[0].strip()
            if line:
                wanted.append(line)

    done, queue, report = set(), list(wanted), []
    while queue:
        slug = queue.pop(0)
        if slug in done:
            continue
        done.add(slug)
        if slug in UNSUPPORTED:
            print(f"::warning::skipping {slug}: {UNSUPPORTED[slug]}")
            continue
        try:
            v = best_version(slug)
        except Exception as e:
            print(f"::warning::could not look up {slug}: {e}")
            continue
        if not v:
            print(f"::warning::{slug} has no Fabric build for {MC}")
            continue
        files = v.get("files") or []
        f = next((x for x in files if x.get("primary")), files[0] if files else None)
        if not f:
            continue
        name = urllib.parse.unquote(f["filename"])
        open(os.path.join(dest, name), "wb").write(get(f["url"]))
        report.append(f"{slug} {v['version_number']} -> {name}")
        for d in v.get("dependencies") or []:
            if d.get("dependency_type") == "required" and d.get("project_id"):
                queue.append(d["project_id"])

    for line in report:
        print("  modrinth:", line)

    if os.path.isdir(user_dir):
        for n in sorted(os.listdir(user_dir)):
            if n.endswith(".jar"):
                shutil.copy(os.path.join(user_dir, n), os.path.join(dest, n))
                print("  your mod:", n)

main()
