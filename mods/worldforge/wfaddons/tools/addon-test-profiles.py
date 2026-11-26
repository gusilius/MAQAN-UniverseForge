#!/usr/bin/env python3
"""Build isolated Forge profiles from each addon's mandatory mods.toml dependency closure."""
import argparse
import json
import re
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument("--addons-root", required=True)
parser.add_argument("--jars", required=True)
parser.add_argument("--output", required=True)
parser.add_argument("--lines", action="store_true")
args = parser.parse_args()

root = Path(args.addons_root).resolve()
jars_dir = Path(args.jars).resolve()
modules = {}
declared_dependencies = {}
compile_dependencies = {}
for folder in sorted(root.glob("wf-*")):
    if not folder.is_dir():
        continue
    props = folder / "gradle.properties"
    toml = folder / "src/main/resources/META-INF/mods.toml"
    if not props.is_file() or not toml.is_file():
        raise SystemExit(f"Missing module metadata for {folder.name}")
    match = re.search(r"^mod_id=(\S+)", props.read_text(encoding="utf-8"), re.M)
    if not match:
        raise SystemExit(f"Missing mod_id in {props}")
    mod_id = match.group(1)
    if mod_id in modules:
        raise SystemExit(f"Duplicate addon mod id: {mod_id}")
    dependencies = []
    text = toml.read_text(encoding="utf-8")
    for block in re.split(r"(?=\[\[dependencies\.)", text):
        dep = re.search(r'^\s*modId\s*=\s*"([^"]+)"', block, re.M)
        mandatory = re.search(r"^\s*mandatory\s*=\s*(true|false)", block, re.M)
        if dep and mandatory and mandatory.group(1).lower() == "true":
            if dep.group(1) not in ("minecraft", "forge", "worldforge"):
                dependencies.append(dep.group(1))
    modules[mod_id] = sorted(set(dependencies))
    declared_dependencies[mod_id] = set(re.findall(
        r'^\s*modId\s*=\s*"([^"]+)"', text, re.M
    ))
    build_gradle = folder / "build.gradle"
    gradle_text = build_gradle.read_text(encoding="utf-8") if build_gradle.is_file() else ""
    compile_dependencies[mod_id] = set(re.findall(
        r"""['"](wf_[A-Za-z0-9_]+)-\*\.jar['"]""", gradle_text
    ))

# A compileOnly jar is a real runtime integration edge: it must be represented
# in mods.toml as mandatory or optional so Forge can order both mods correctly.
for mod_id, references in compile_dependencies.items():
    missing_modules = sorted(references - modules.keys())
    if missing_modules:
        raise SystemExit(f"{mod_id} compileOnly references missing addon modules: {missing_modules}")
    undeclared = sorted(references - declared_dependencies[mod_id])
    if undeclared:
        raise SystemExit(
            f"{mod_id} compileOnly addon dependencies lack mods.toml declarations: {undeclared}"
        )

def jar_for(mod_id):
    matches = sorted(p for p in jars_dir.glob(f"{mod_id}-*.jar")
                     if not p.name.endswith("-sources.jar"))
    if len(matches) != 1:
        raise SystemExit(f"Expected exactly one jar for {mod_id}, found {[p.name for p in matches]}")
    return matches[0].name

core = sorted(p for p in jars_dir.glob("worldforge-*.jar")
              if not p.name.endswith("-sources.jar"))
if len(core) != 1:
    raise SystemExit(f"Expected exactly one WorldForge core jar, found {[p.name for p in core]}")

profiles = [{"id": "core-only", "addons": [], "jars": [core[0].name]}]
for root_id in sorted(modules):
    visiting, visited, ordered = [], set(), []

    def visit(mod_id):
        if mod_id in visited:
            return
        if mod_id in visiting:
            raise SystemExit(f"Mandatory dependency cycle for {root_id}: {mod_id}")
        if mod_id not in modules:
            raise SystemExit(f"{root_id} requires missing addon {mod_id}; dependency graph is incomplete")
        visiting.append(mod_id)
        for dependency in modules[mod_id]:
            visit(dependency)
        visiting.pop()
        visited.add(mod_id)
        ordered.append(mod_id)

    visit(root_id)
    profiles.append({
        "id": root_id,
        "addons": ordered,
        "jars": [core[0].name] + [jar_for(mod_id) for mod_id in ordered],
    })

Path(args.output).write_text(json.dumps(profiles, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
if args.lines:
    # stdout is a machine-readable protocol consumed by mapfile; never mix diagnostics into it.
    for profile in profiles:
        print(profile["id"] + "|" + ",".join(profile["jars"]) + "|" + ",".join(profile["addons"]))
    print(f"ADDON_ISOLATION_PROFILE_COUNT={len(profiles)}", file=__import__("sys").stderr)
else:
    print(f"ADDON_ISOLATION_PROFILE_COUNT={len(profiles)}")
