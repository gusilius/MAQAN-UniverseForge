#!/usr/bin/env bash
# Full Forge runtime campaign on the real Planetos geography package.
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
MAP_SOURCE="\${1:?usage: autonomous-campaign.sh <planetos.worldmap> <addon-jar-dir> <output-dir>}"
JARS="\${2:?addon jar directory required}"
OUTPUT="\${3:?output directory required}"
MAP_ID="planetos"
FORGE="1.20.1-47.3.0"
MAX_RUNTIME_SECONDS="\${WF_CAMPAIGN_TIMEOUT_SECONDS:-3300}"
MAP_SOURCE="$(cd "$(dirname "$MAP_SOURCE")" && pwd)/$(basename "$MAP_SOURCE")"
JARS="$(cd "$JARS" && pwd)"
mkdir -p "$OUTPUT"
OUTPUT="$(cd "$OUTPUT" && pwd)"
WORK="$(mktemp -d)"
SERVER_PID=""
cleanup() {
  if [ -f "$WORK/server.log" ]; then cp "$WORK/server.log" "$OUTPUT/server.log" 2>/dev/null || true; fi
  if [ -f "$WORK/in.txt" ]; then printf '%s\n' "stop" >> "$WORK/in.txt" 2>/dev/null || true; fi
  if [ -n "$SERVER_PID" ]; then
    for _ in $(seq 1 30); do kill -0 "$SERVER_PID" 2>/dev/null || break; sleep 1; done
    kill "$SERVER_PID" 2>/dev/null || true
  fi
  rm -rf "$WORK"
}
trap cleanup EXIT
[ -f "$MAP_SOURCE" ] || { echo "ERROR: Planetos map package missing: $MAP_SOURCE"; exit 2; }
[ -d "$JARS" ] || { echo "ERROR: addon jar directory missing: $JARS"; exit 2; }
expected_addons=$(find "$SCRIPT_DIR/.." -mindepth 1 -maxdepth 1 -type d -name 'wf-*' | wc -l)
jar_count=$(find "$JARS" -maxdepth 1 -type f -name '*.jar' | wc -l)
[ "$jar_count" -ge "$expected_addons" ] || { echo "ERROR: incomplete addon distribution ($jar_count jars, expected at least $expected_addons)"; exit 2; }
cd "$WORK"
curl -fsSL -o installer.jar "https://maven.minecraftforge.net/net/minecraftforge/forge/$FORGE/forge-$FORGE-installer.jar"
java -jar installer.jar --installServer > install.log 2>&1 || { tail -60 install.log; exit 2; }
mkdir -p mods config/worldforge/maps world/datapacks/planetos/data/worldforge/dimension
cp "$JARS"/*.jar mods/
python3 - "$MAP_SOURCE" "config/worldforge/maps/planetos.worldmap" <<'PY'
import json, pathlib, sys, zipfile
source, target = pathlib.Path(sys.argv[1]), pathlib.Path(sys.argv[2])
required = {"meta.json", "biomes.png", "height16.png", "provinces.png", "titles.json", "realms.json"}
with zipfile.ZipFile(source, "r") as src:
    names = {pathlib.PurePosixPath(n).name for n in src.namelist() if not n.endswith("/")}
    missing = required - names
    if missing:
        raise SystemExit("Planetos package lacks required files: " + ", ".join(sorted(missing)))
    files = {}
    for name in names:
        matches = [n for n in src.namelist() if pathlib.PurePosixPath(n).name == name]
        if len(matches) != 1:
            raise SystemExit(f"Ambiguous map package entry: {name}")
        files[name] = src.read(matches[0])
realms = json.loads(files["realms.json"].decode("utf-8"))
if not isinstance(realms, dict) or not isinstance(realms.get("realms"), list):
    raise SystemExit("Planetos realms.json has an unsupported schema")
authored_realms = len(realms["realms"])
realms["realms"] = []
realms["relations"] = []
files["realms.json"] = json.dumps(realms, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
files.pop("mapitem_realms.bin", None)
with zipfile.ZipFile(target, "w", zipfile.ZIP_DEFLATED) as dst:
    for name, data in sorted(files.items()):
        dst.writestr(name, data)
with zipfile.ZipFile(target, "r") as check:
    checked = json.loads(check.read("realms.json").decode("utf-8"))
    if checked["realms"]:
        raise SystemExit("Refusing to start: authored realms remain in campaign map")
print("PLANETOS_MAP_PREPARED=1")
print(f"AUTHORED_REALMS_REMOVED={authored_realms}")
print(f"GEOGRAPHY_FILES_RETAINED={len(files)}")
PY
cat > world/datapacks/planetos/pack.mcmeta <<'P'
{"pack":{"pack_format":15,"description":"Planetos geography-only autonomous campaign"}}
P
cat > world/datapacks/planetos/data/worldforge/dimension/planetos.json <<'P'
{
  "type": "worldforge:mapworld",
  "generator": {
    "type": "worldforge:image",
    "map": "planetos"
  }
}
P
echo "eula=true" > eula.txt
cat > server.properties <<'P'
online-mode=false
level-name=world
level-type=minecraft\:normal
level-seed=20261114
generate-structures=false
view-distance=4
simulation-distance=4
spawn-protection=0
enable-command-block=false
P
: > in.txt
(tail -n +1 -f in.txt | java -Xms1G -Xmx4G @user_jvm_args.txt @libraries/net/minecraftforge/forge/$FORGE/unix_args.txt nogui > server.log 2>&1) &
SERVER_PID=$!
echo "WAITING_FOR_FORGE_STARTUP=1"
for i in $(seq 1 150); do
  if grep -q 'Done (' server.log 2>/dev/null; then break; fi
  if grep -aqE 'Mod loading has failed|Failed to start|Exception in thread "main"|java.lang.Error' server.log 2>/dev/null; then
    tail -120 server.log; echo "ERROR: Forge server failed to start"; exit 3
  fi
  kill -0 "$SERVER_PID" 2>/dev/null || { tail -120 server.log; echo "ERROR: Forge server process exited"; exit 3; }
  sleep 2
done
grep -q 'Done (' server.log || { tail -120 server.log; echo "ERROR: Forge startup timed out"; exit 3; }
printf '%s\n' "wfworldsim campaign start $MAP_ID" >> in.txt
echo "WAITING_FOR_CAMPAIGN_PREFLIGHT=1"
for i in $(seq 1 90); do
  if grep -q 'map-only autonomous campaign started' server.log; then break; fi
  if grep -qiE 'Campaign map must retain|Campaign requires|Native WorldSim engine is unavailable|Unknown or incomplete command' server.log; then
    tail -160 server.log; echo "ERROR: autonomous campaign preflight rejected the world"; exit 4
  fi
  kill -0 "$SERVER_PID" 2>/dev/null || { tail -160 server.log; echo "ERROR: server exited during campaign preflight"; exit 4; }
  sleep 2
done
grep -q 'map-only autonomous campaign started' server.log || { tail -160 server.log; echo "ERROR: campaign command was not accepted"; exit 4; }
echo "AUTONOMOUS_CAMPAIGN_RUNNING=1"
started=$(date +%s)
last_status=$started
REPORT="world/worldforge/autonomous-campaign/campaign-report.json"
while [ ! -s "$REPORT" ]; do
  kill -0 "$SERVER_PID" 2>/dev/null || { tail -160 server.log; echo "ERROR: server exited before campaign completion"; exit 5; }
  now=$(date +%s)
  if [ $((now - started)) -ge "$MAX_RUNTIME_SECONDS" ]; then
    printf '%s\n' "wfworldsim campaign status" >> in.txt
    tail -160 server.log
    echo "ERROR: campaign did not reach stable four-generation acceptance before wall-clock timeout"
    exit 6
  fi
  if [ $((now - last_status)) -ge 60 ]; then
    printf '%s\n' "wfworldsim campaign status" >> in.txt
    last_status=$now
    echo "CAMPAIGN_WALL_SECONDS=$((now - started))"
  fi
  sleep 5
done
printf '%s\n' "stop" >> in.txt
for _ in $(seq 1 60); do kill -0 "$SERVER_PID" 2>/dev/null || break; sleep 2; done
kill "$SERVER_PID" 2>/dev/null || true
SERVER_PID=""
cp "$REPORT" "$OUTPUT/campaign-report.json"
cp "world/worldforge/autonomous-campaign/chronicle-book.md" "$OUTPUT/chronicle-book.md"
cp server.log "$OUTPUT/server.log"
python3 - "$OUTPUT/campaign-report.json" "$OUTPUT/addon-inventory.json" "$JARS" <<'PY'
import json, pathlib, sys
report_path, inventory_path, jars_dir = pathlib.Path(sys.argv[1]), pathlib.Path(sys.argv[2]), pathlib.Path(sys.argv[3])
report = json.loads(report_path.read_text(encoding="utf-8"))
if report.get("status") != "PASS" or report.get("target_dynasty_generations") != 4:
    raise SystemExit("Campaign report does not confirm four canonical ruler generations")
for key in ("stable_states", "characters", "settlements", "materialized_settlements", "completed_buildings", "canonical_ruler_generations_verified", "world_events", "chronicle_entries"):
    if int(report.get(key, 0)) <= 0:
        raise SystemExit(f"Campaign report is missing required generated data: {key}")
book = inventory_path.with_name("chronicle-book.md")
if not book.is_file() or book.stat().st_size < 100:
    raise SystemExit("Historical book is missing or empty")
inventory = sorted(p.name for p in jars_dir.glob("*.jar"))
inventory_path.write_text(json.dumps({
    "installed_addon_jars": inventory,
    "count": len(inventory),
    "runtime": "Forge server campaign; see campaign-report.json and server.log"
}, ensure_ascii=False, indent=2), encoding="utf-8")
print("AUTONOMOUS_CAMPAIGN_STATUS=PASS")
for key in ("map", "physical_world_dimension", "elapsed_years", "stable_states", "characters", "settlements", "materialized_settlements", "completed_buildings", "canonical_ruler_generations_verified", "world_events", "chronicle_entries"):
    print(f"AUTONOMOUS_CAMPAIGN_{key.upper()}={report.get(key)}")
print(f"AUTONOMOUS_CAMPAIGN_ADDON_JARS={len(inventory)}")
PY
