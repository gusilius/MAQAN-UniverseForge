#!/usr/bin/env bash
# Build only the requested addons plus their transitive compileOnly dependency closure.
set -euo pipefail
CORE="${1:-../wfmod}"
shift || true
if [ "$#" -eq 0 ]; then echo "Usage: $0 <core-dir> <wf-addon> [wf-addon ...]" >&2; exit 2; fi
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
python3 - "$@" <<'PY' > "$ROOT/.selected-addon-order"
import subprocess, sys
targets=set(sys.argv[1:])
raw=subprocess.check_output([sys.executable,"tools/build_order.py","--deps"],text=True)
deps={}
order=[]
for line in raw.splitlines():
    module, values=line.split("|",1)
    order.append(module)
    deps[module]=set(filter(None,values.split(",")))
unknown=targets-set(order)
if unknown:
    raise SystemExit("Unknown addon(s): "+", ".join(sorted(unknown)))
needed=set()
def include(module):
    if module in needed: return
    needed.add(module)
    for dep in deps[module]: include(dep)
for target in targets: include(target)
print(" ".join(module for module in order if module in needed))
PY
mapfile -t MODULES < <(tr ' ' '\n' < .selected-addon-order | sed '/^$/d')
rm -f .selected-addon-order
( cd "$CORE" && gradle build --no-daemon )
CORE_JAR="$(find "$CORE/build/libs" -maxdepth 1 -type f -name 'worldforge-*.jar' ! -name '*-sources.jar' -print -quit)"
[ -n "$CORE_JAR" ] || { echo "ERROR: WorldForge core jar missing"; exit 1; }
declare -A DEP_MAP=()
while IFS='|' read -r module dep_list; do DEP_MAP["$module"]="$dep_list"; done < <(python3 tools/build_order.py --deps)
for module in "${MODULES[@]}"; do
  echo "=== TARGETED ADDON BUILD: $module"
  mkdir -p "$module/libs"
  rm -f "$module"/libs/wf_*.jar
  cp "$CORE_JAR" "$module/libs/"
  IFS=',' read -r -a deps <<< "${DEP_MAP[$module]:-}"
  for dep in "${deps[@]}"; do
    [ -n "$dep" ] || continue
    found=0
    for jar in "$dep"/build/libs/wf_*.jar; do
      [ -f "$jar" ] || continue
      case "$jar" in *-sources.jar) continue ;; esac
      cp "$jar" "$module/libs/"
      found=1
    done
    [ "$found" -eq 1 ] || { echo "ERROR: missing dependency artifact $dep for $module"; exit 1; }
  done
  ( cd "$module" && gradle build --no-daemon -q )
done
echo "TARGETED ADDON BUILDS PASSED: ${MODULES[*]}"
