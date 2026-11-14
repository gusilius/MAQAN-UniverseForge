#!/usr/bin/env bash
# Full Forge runtime campaign on the real Planetos geography package.
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
MAP_SOURCE="${1:?usage: autonomous-campaign.sh <planetos.worldmap> <addon-jar-dir> <output-dir>}"
JARS="${2:?addon jar directory required}"
OUTPUT="${3:?output directory required}"
MAP_ID="planetos"
FORGE="1.20.1-47.3.0"
# Derive reproducibility only from the selected map package; no extra scenario seed input.
MAP_SEED="$(sha256sum "$MAP_SOURCE" | awk '{print substr($1,1,16)}')"
MAX_RUNTIME_SECONDS="${WF_CAMPAIGN_TIMEOUT_SECONDS:-3300}"
MAP_SOURCE="$(cd "$(dirname "$MAP_SOURCE")" && pwd)/$(basename "$MAP_SOURCE")"
JARS="$(cd "$JARS" && pwd)"
mkdir -p "$OUTPUT"
OUTPUT="$(cd "$OUTPUT" && pwd)"
WORK="$(mktemp -d)"
# Convert Git Bash temporary paths before passing JVM output paths on Windows.
JAVA_WORK="$(cygpath -m "$WORK")"
SERVER_PID=""
SERVER_EXIT_CODE="not_observed"
CURRENT_STAGE="preflight"
THREAD_DUMP_COUNT=0
capture_server_threads() {
  local stamp pid
  stamp="$(date +%s)"
  jcmd -l > "$OUTPUT/jcmd-processes-$stamp.txt" 2>&1 || return 0
  pid="$(awk '/BootstrapLauncher|net.minecraft.server.Main|MinecraftServer/ {print $1; exit}' "$OUTPUT/jcmd-processes-$stamp.txt")"
  [ -n "$pid" ] || return 0
  jcmd "$pid" Thread.print -l > "$OUTPUT/thread-dump-$stamp.txt" 2>&1 || true
  THREAD_DUMP_COUNT=$((THREAD_DUMP_COUNT + 1))
  echo "CAMPAIGN_THREAD_DUMP=$stamp"
}
campaign_exit() {
  local exit_code="$1"
  trap - EXIT
  if [ -f "$WORK/in.txt" ] && [ -n "$SERVER_PID" ]; then
    printf '%s\n' "stop" >> "$WORK/in.txt" 2>/dev/null || true
    for _ in $(seq 1 10); do kill -0 "$SERVER_PID" 2>/dev/null || break; sleep 1; done
    taskkill.exe //PID "$SERVER_PID" //T //F >/dev/null 2>&1 || kill "$SERVER_PID" 2>/dev/null || true
    if wait "$SERVER_PID"; then SERVER_EXIT_CODE=0; else SERVER_EXIT_CODE=$?; fi
    echo "CAMPAIGN_JVM_EXIT_CODE=$SERVER_EXIT_CODE"
  fi
  python3 "$SCRIPT_DIR/campaign-diagnostics.py" --work "$WORK" --output "$OUTPUT" --exit-code "$exit_code" --stage "$CURRENT_STAGE" --run-id "${GITHUB_RUN_ID:-local}" --commit "${GITHUB_SHA:-unknown}" --seed "$MAP_SEED" || true
  rm -rf "$WORK"
  exit "$exit_code"
}
trap 'campaign_exit $?' EXIT
[ -f "$MAP_SOURCE" ] || { echo "ERROR: Planetos map package missing: $MAP_SOURCE"; exit 2; }
[ -d "$JARS" ] || { echo "ERROR: addon jar directory missing: $JARS"; exit 2; }
expected_addons=$(find "$SCRIPT_DIR/.." -mindepth 1 -maxdepth 1 -type d -name 'wf-*' | wc -l)
jar_count=$(find "$JARS" -maxdepth 1 -type f -name '*.jar' | wc -l)
[ "$jar_count" -ge "$expected_addons" ] || { echo "ERROR: incomplete addon distribution ($jar_count jars, expected at least $expected_addons)"; exit 2; }
cd "$WORK"
CURRENT_STAGE="forge_install"
curl -fsSL -o installer.jar "https://maven.minecraftforge.net/net/minecraftforge/forge/$FORGE/forge-$FORGE-installer.jar"
java -jar installer.jar --installServer > install.log 2>&1 || { tail -60 install.log; exit 2; }
CURRENT_STAGE="map_preparation"
mkdir -p mods config/worldforge/maps
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
# The mod already registers worldforge:planetos in its built-in dimension data; do not shadow it in a second datapack.
echo "eula=true" > eula.txt
cat > server.properties <<P
online-mode=false
level-name=world
level-type=minecraft\:normal
level-seed=$MAP_SEED
generate-structures=false
view-distance=4
simulation-distance=4
spawn-protection=0
enable-command-block=false
P
: > in.txt
CURRENT_STAGE="forge_startup"
java -Xms1G -Xmx4G \
  -XX:+HeapDumpOnOutOfMemoryError \
  -XX:HeapDumpPath="$JAVA_WORK/java_pid%p.hprof" \
  -XX:ErrorFile="$JAVA_WORK/hs_err_pid%p.log" \
  -Xlog:gc*:file="$JAVA_WORK/gc-%p.log":time,uptime,level,tags \
  -Dworldforge.campaign.empty-bootstrap=true \
  @user_jvm_args.txt @libraries/net/minecraftforge/forge/$FORGE/win_args.txt nogui \
  < <(tail -n +1 -f in.txt) > server.log 2>&1 &
SERVER_PID=$!
echo "WAITING_FOR_FORGE_STARTUP=1"
for i in $(seq 1 150); do
  if grep -q 'Done (' server.log 2>/dev/null; then break; fi
  if grep -aqE 'Mod loading has failed|Failed to start|Exception in thread "main"|java.lang.Error' server.log 2>/dev/null; then
    tail -120 server.log; echo "ERROR: Forge server failed to start"; exit 3
  fi
  kill -0 "$SERVER_PID" 2>/dev/null || { if wait "$SERVER_PID"; then SERVER_EXIT_CODE=0; else SERVER_EXIT_CODE=$?; fi; tail -120 server.log; echo "ERROR: Forge JVM exited with code $SERVER_EXIT_CODE"; exit 3; }
  sleep 2
done
grep -q 'Done (' server.log || { tail -120 server.log; echo "ERROR: Forge startup timed out"; exit 3; }
CURRENT_STAGE="campaign_preflight"
printf '%s\n' "wfworldsim campaign start $MAP_ID" >> in.txt
echo "WAITING_FOR_CAMPAIGN_PREFLIGHT=1"
for i in $(seq 1 90); do
  if grep -q 'map-only autonomous campaign started' server.log; then break; fi
  if grep -qiE 'Campaign map must retain|Campaign requires|Native WorldSim engine is unavailable|Unknown or incomplete command' server.log; then
    tail -160 server.log; echo "ERROR: autonomous campaign preflight rejected the world"; exit 4
  fi
  kill -0 "$SERVER_PID" 2>/dev/null || { if wait "$SERVER_PID"; then SERVER_EXIT_CODE=0; else SERVER_EXIT_CODE=$?; fi; tail -160 server.log; echo "ERROR: Forge JVM exited during campaign preflight with code $SERVER_EXIT_CODE"; exit 4; }
  sleep 2
done
grep -q 'map-only autonomous campaign started' server.log || { tail -160 server.log; echo "ERROR: campaign command was not accepted"; exit 4; }
echo "AUTONOMOUS_CAMPAIGN_RUNNING=1"
CURRENT_STAGE="campaign_simulation"
started=$(date +%s)
last_status=$started
last_thread_dump=$started
REPORT="world/worldforge/autonomous-campaign/campaign-report.json"
while [ ! -s "$REPORT" ]; do
  kill -0 "$SERVER_PID" 2>/dev/null || { if wait "$SERVER_PID"; then SERVER_EXIT_CODE=0; else SERVER_EXIT_CODE=$?; fi; tail -160 server.log; echo "ERROR: Forge JVM exited before campaign completion with code $SERVER_EXIT_CODE"; exit 5; }
  now=$(date +%s)
  if [ $((now - started)) -ge "$MAX_RUNTIME_SECONDS" ]; then
    printf '%s\n' "wfworldsim campaign status" >> in.txt
    tail -160 server.log
    echo "ERROR: campaign did not reach stable four-generation acceptance before wall-clock timeout"
    exit 6
  fi
  if [ $((now - last_thread_dump)) -ge 90 ] && [ "$THREAD_DUMP_COUNT" -lt 8 ]; then
    capture_server_threads
    last_thread_dump=$now
  fi
  if [ $((now - last_status)) -ge 60 ]; then
    printf '%s\n' "wfworldsim campaign status" >> in.txt
    last_status=$now
    echo "CAMPAIGN_WALL_SECONDS=$((now - started))"
  fi
  sleep 5
done
CURRENT_STAGE="campaign_report_validation"
printf '%s\n' "stop" >> in.txt
for _ in $(seq 1 60); do kill -0 "$SERVER_PID" 2>/dev/null || break; sleep 2; done
kill "$SERVER_PID" 2>/dev/null || true
if wait "$SERVER_PID"; then SERVER_EXIT_CODE=0; else SERVER_EXIT_CODE=$?; fi
echo "CAMPAIGN_JVM_EXIT_CODE=$SERVER_EXIT_CODE"
SERVER_PID=""
cp "$REPORT" "$OUTPUT/campaign-report.json"
cp "world/worldforge/autonomous-campaign/chronicle-book.md" "$OUTPUT/chronicle-book.md"
if [ -s "world/worldforge/autonomous-campaign/performance-profile.json" ]; then
  cp "world/worldforge/autonomous-campaign/performance-profile.json" "$OUTPUT/performance-profile.json"
fi
cp server.log "$OUTPUT/server.log"
python3 - "$OUTPUT/campaign-report.json" "$OUTPUT/addon-inventory.json" "$JARS" <<'PY'
import json, pathlib, sys
report_path, inventory_path, jars_dir = pathlib.Path(sys.argv[1]), pathlib.Path(sys.argv[2]), pathlib.Path(sys.argv[3])
report = json.loads(report_path.read_text(encoding="utf-8"))
if report.get("status") != "PASS" or report.get("target_dynasty_generations") != 4:
    raise SystemExit("Campaign report does not confirm four canonical ruler generations")
if int(report.get("canonical_ruler_generations_verified", 0)) != int(report.get("stable_states", 0)):
    raise SystemExit("Not every surviving state has four verified canonical ruler generations")
if int(report.get("materialized_settlements", 0)) != int(report.get("settlements", 0)):
    raise SystemExit("Not every emergent settlement reached physical local-LOD materialization")
if int(report.get("physically_built_settlements", 0)) != int(report.get("settlements", 0)):
    raise SystemExit("Not every emergent settlement completed a physical blueprint in the Planetos dimension")
if int(report.get("physically_completed_buildings", 0)) < int(report.get("settlements", 0)):
    raise SystemExit("The physical building count is below the one-building-per-settlement acceptance threshold")
if not report.get("physical_world_dimension"):
    raise SystemExit("Campaign did not run in a registered Planetos map dimension")
for key in ("stable_states", "characters", "settlements", "materialized_settlements", "completed_buildings", "physically_completed_buildings", "physically_built_settlements", "canonical_ruler_generations_verified", "origin_race_characters", "origin_distinct_races", "origin_class_characters", "origin_profession_characters", "world_events", "chronicle_entries"):
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
for key in ("map", "physical_world_dimension", "elapsed_years", "stable_states", "characters", "settlements", "materialized_settlements", "completed_buildings", "canonical_ruler_generations_verified", "origin_race_characters", "origin_distinct_races", "origin_class_characters", "origin_profession_characters", "world_events", "chronicle_entries"):
    print(f"AUTONOMOUS_CAMPAIGN_{key.upper()}={report.get(key)}")
print(f"AUTONOMOUS_CAMPAIGN_ADDON_JARS={len(inventory)}")
PY
