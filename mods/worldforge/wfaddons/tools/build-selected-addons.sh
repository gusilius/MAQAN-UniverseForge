#!/usr/bin/env bash
# Build only requested addons and their transitive compileOnly addon dependencies.
set -euo pipefail
CORE="../wfmod"
if [ "$#" -gt 0 ]; then CORE="$1"; shift; fi
if [ "$#" -eq 0 ]; then
  echo "Usage: $0 [core-dir] <wf-addon> [wf-addon ...]" >&2
  exit 2
fi
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
PLAN="$ROOT/.selected-addon-build.tmp"
trap 'rm -f "$PLAN"' EXIT

python3 - "$@" > "$PLAN" <<'PY'
import subprocess, sys
targets = set(sys.argv[1:])
raw = subprocess.check_output([sys.executable, "tools/build_order.py", "--deps"], text=True)
deps, order = {}, []
for line in raw.splitlines():
    module, values = line.split("|", 1)
    module = module.rstrip("\r")
    values = values.rstrip("\r")
    order.append(module)
    deps[module] = set(x.rstrip("\r") for x in values.split(",") if x.rstrip("\r"))
unknown = targets - set(order)
if unknown:
    raise SystemExit("Unknown addon(s): " + ", ".join(sorted(unknown)))
needed = set()
def include(module):
    if module in needed:
        return
    needed.add(module)
    for dependency in deps[module]:
        include(dependency)
for target in targets:
    include(target)
for module in order:
    if module in needed:
        print(module + "|" + ",".join(sorted(deps[module])))
PY

python3 - "$PLAN" "$@" <<'PY'
import sys
plan_path, *targets = sys.argv[1:]
with open(plan_path, encoding="utf-8") as source:
    lines = [line.rstrip("\\r\\n") for line in source if line.strip()]
modules = [line.split("|", 1)[0] for line in lines]
missing = sorted(set(targets) - set(modules))
if missing:
    raise SystemExit("ERROR: requested addon(s) missing from dependency-closure plan: " + ", ".join(missing))
if len(modules) != len(set(modules)):
    raise SystemExit("ERROR: dependency-closure plan contains duplicate addon rows")
print("=== VERIFIED TARGETED ADDON BUILD PLAN ===")
for module in modules:
    print("  " + module)
print("=== ALL REQUESTED ADDONS ARE IN THE BUILD PLAN ===")
PY

( cd "$CORE" && gradle build --no-daemon )
CORE_JAR="$(find "$CORE/build/libs" -maxdepth 1 -type f -name 'worldforge-*.jar' ! -name '*-sources.jar' -print -quit)"
if [ -z "$CORE_JAR" ]; then
  echo "ERROR: WorldForge core jar missing" >&2
  exit 1
fi

while IFS='|' read -r module dep_list; do
  module="$(printf '%s' "$module" | tr -d '\r')"
  dep_list="$(printf '%s' "$dep_list" | tr -d '\r')"
  [ -n "$module" ] || continue
  echo "=== TARGETED ADDON BUILD: $module"
  mkdir -p "$module/libs"
  rm -f "$module"/libs/wf_*.jar
  cp "$CORE_JAR" "$module/libs/"

  if [ -n "$dep_list" ]; then
    for dep in $(printf '%s' "$dep_list" | tr ',' ' '); do
      dep="$(printf '%s' "$dep" | tr -d '\r')"
      [ -n "$dep" ] || continue
      found=0
      for jar in "$dep"/build/libs/wf_*.jar; do
        [ -f "$jar" ] || continue
        case "$jar" in *-sources.jar) continue ;; esac
        cp "$jar" "$module/libs/"
        found=1
      done
      if [ "$found" -ne 1 ]; then
        echo "ERROR: missing dependency artifact $dep for $module" >&2
        exit 1
      fi
    done
  fi

  ( cd "$module" && gradle build --no-daemon -q )
done < "$PLAN"

echo "TARGETED ADDON BUILDS PASSED"
