#!/usr/bin/env bash
# Full Forge runtime campaign on the real Planetos geography package.
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
MAP_SOURCE="${1:?usage: autonomous-campaign.sh <vanilla|worldmap> <addon-jar-dir> <output-dir>}"
JARS="${2:?addon jar directory required}"
OUTPUT="${3:?output directory required}"
MAP_ID="${WF_CAMPAIGN_MAP_ID:-planetos}"
FORGE="1.20.1-47.3.0"
VANILLA_CAMPAIGN=0
if [ "$MAP_SOURCE" = "vanilla" ]; then
  VANILLA_CAMPAIGN=1
  MAP_ID="vanilla"
  MAP_SEED="${WF_VANILLA_SEED:-424242}"
else
  MAP_SEED="$(sha256sum "$MAP_SOURCE" | awk '{print substr($1,1,16)}')"
fi
MAX_RUNTIME_SECONDS="${WF_CAMPAIGN_TIMEOUT_SECONDS:-3300}"
if [ "$VANILLA_CAMPAIGN" -eq 0 ]; then MAP_SOURCE="$(cd "$(dirname "$MAP_SOURCE")" && pwd)/$(basename "$MAP_SOURCE")"; fi
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
if [ "$VANILLA_CAMPAIGN" -eq 0 ]; then [ -f "$MAP_SOURCE" ] || { echo "ERROR: image-derived map package missing: $MAP_SOURCE"; exit 2; }; fi
[ -d "$JARS" ] || { echo "ERROR: addon jar directory missing: $JARS"; exit 2; }
expected_addons=$(find "$SCRIPT_DIR/.." -mindepth 1 -maxdepth 1 -type d -name 'wf-*' | wc -l)
jar_count=$(find "$JARS" -maxdepth 1 -type f -name '*.jar' | wc -l)
[ "$jar_count" -ge "$expected_addons" ] || { echo "ERROR: incomplete addon distribution ($jar_count jars, expected at least $expected_addons)"; exit 2; }
cd "$WORK"
CURRENT_STAGE="forge_install"
curl -fsSL -o installer.jar "https://maven.minecraftforge.net/net/minecraftforge/forge/$FORGE/forge-$FORGE-installer.jar"
java -jar installer.jar --installServer > install.log 2>&1 || { tail -60 install.log; exit 2; }
CURRENT_STAGE="world_preparation"
mkdir -p mods
cp "$JARS"/*.jar mods/
if [ "$VANILLA_CAMPAIGN" -eq 0 ]; then
  mkdir -p config/worldforge/maps
  python3 - "$MAP_SOURCE" "config/worldforge/maps/$MAP_ID.worldmap" <<'PY'
import json, pathlib, sys, zipfile
source, target = pathlib.Path(sys.argv[1]), pathlib.Path(sys.argv[2])
required = {"meta.json", "biomes.png", "height16.png", "provinces.png", "titles.json", "realms.json"}
with zipfile.ZipFile(source, "r") as src:
    names = {pathlib.PurePosixPath(n).name for n in src.namelist() if not n.endswith("/")}
    missing = required - names
    if missing: raise SystemExit("Map package lacks required files: " + ", ".join(sorted(missing)))
    files = {}
    for name in names:
        matches = [n for n in src.namelist() if pathlib.PurePosixPath(n).name == name]
        if len(matches) != 1: raise SystemExit(f"Ambiguous map package entry: {name}")
        files[name] = src.read(matches[0])
realms = json.loads(files["realms.json"].decode("utf-8"))
if not isinstance(realms, dict) or not isinstance(realms.get("realms"), list):
    raise SystemExit("Map realms.json has an unsupported schema")
authored_realms = len(realms["realms"])
realms["realms"] = []
realms["relations"] = []
files["realms.json"] = json.dumps(realms, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
files.pop("mapitem_realms.bin", None)
with zipfile.ZipFile(target, "w", zipfile.ZIP_DEFLATED) as dst:
    for name, data in sorted(files.items()): dst.writestr(name, data)
print("IMAGE_MAP_PREPARED=1")
print(f"AUTHORED_REALMS_REMOVED={authored_realms}")
print(f"GEOGRAPHY_FILES_RETAINED={len(files)}")
PY
else
  echo "VANILLA_WORLD_SELECTED=1"
fi
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
java -Xms1G -Xmx4G -Dworldforge.campaign.acceptance=true \
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
CURRENT_STAGE="modpack_smoke"
for command in "list" "wftroops types" "wftroops fort list" "wftroops list" "wfsettle list" "wfform" "wfaction list" "wfworldsim status"; do
  printf '%s\n' "$command" >> in.txt
  sleep 2
done
CURRENT_STAGE="geography_preflight"
if [ "$VANILLA_CAMPAIGN" -eq 1 ]; then
  # Capture a bounded, real 256-chunk region around spawn; no synthetic geography fixture.
  printf '%s\n' "forceload add -128 -128 127 127" >> in.txt
  sleep 8
  geography_ready=0
  for _ in $(seq 1 90); do
    printf '%s\n' "wfworldsim geography" >> in.txt
    if grep -aqE 'Vanilla geography: regions=[1-9][0-9]*, samples=[1-9][0-9]*, pending_chunks=0' server.log; then geography_ready=1; break; fi
    kill -0 "$SERVER_PID" 2>/dev/null || { tail -160 server.log; echo "ERROR: vanilla server stopped during geography capture"; exit 3; }
    sleep 2
  done
  [ "$geography_ready" = 1 ] || { tail -160 server.log; echo "ERROR: vanilla geography cache did not capture the bounded spawn region"; exit 3; }
fi
CURRENT_STAGE="campaign_preflight"
preflight_log_lines=$(wc -l < server.log)
printf '%s\n' "wfworldsim campaign start $MAP_ID" >> in.txt
echo "WAITING_FOR_CAMPAIGN_PREFLIGHT=1"
for i in $(seq 1 90); do
  if tail -n +$((preflight_log_lines + 1)) server.log | grep -q 'map-only autonomous campaign started'; then break; fi
  if tail -n +$((preflight_log_lines + 1)) server.log | grep -qiE 'Campaign map must retain|Campaign requires|Native WorldSim engine is unavailable|Unknown or incomplete command'; then
    tail -160 server.log; echo "ERROR: autonomous campaign preflight rejected the world"; exit 4
  fi
  kill -0 "$SERVER_PID" 2>/dev/null || { if wait "$SERVER_PID"; then SERVER_EXIT_CODE=0; else SERVER_EXIT_CODE=$?; fi; tail -160 server.log; echo "ERROR: Forge JVM exited during campaign preflight with code $SERVER_EXIT_CODE"; exit 4; }
  sleep 2
done
tail -n +$((preflight_log_lines + 1)) server.log | grep -q 'map-only autonomous campaign started' || { tail -160 server.log; echo "ERROR: campaign command was not accepted"; exit 4; }
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
  if [ $((now - last_thread_dump)) -ge 90 ]; then
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
target_generations = int(report.get("target_dynasty_generations", 0))
if report.get("status") != "PASS" or target_generations < 1:
    raise SystemExit("Campaign report does not confirm the configured canonical ruler-generation target")
if int(report.get("canonical_ruler_generations_verified", 0)) != int(report.get("stable_states", 0)):
    raise SystemExit("Not every surviving state has the configured number of verified canonical ruler generations")
if int(report.get("materialized_settlements", 0)) != int(report.get("settlements", 0)):
    raise SystemExit("Not every emergent settlement reached physical local-LOD materialization")
if int(report.get("physically_built_settlements", 0)) != int(report.get("settlements", 0)):
    raise SystemExit("Not every emergent settlement completed a physical blueprint in the selected world dimension")
if int(report.get("physically_completed_buildings", 0)) < int(report.get("settlements", 0)):
    raise SystemExit("The physical building count is below the one-building-per-settlement acceptance threshold")
if not report.get("physical_world_dimension"):
    raise SystemExit("Campaign did not run in a registered physical world dimension")
for key in ("stable_states", "characters", "settlements", "materialized_settlements", "completed_buildings", "physically_completed_buildings", "physically_built_settlements", "canonical_ruler_generations_verified", "named_realms", "canonical_rulers", "statecraft_titles", "territorial_titles", "title_hierarchy_links", "barony_titles", "county_titles", "duchy_titles", "kingdom_titles", "named_dynasties", "vassal_contracts", "political_ownership_samples", "origin_race_characters", "origin_distinct_races", "origin_class_characters", "origin_profession_characters", "culture_characters", "distinct_cultures", "geography_regions", "geography_samples", "world_events", "chronicle_entries"):
    if int(report.get(key, 0)) <= 0:
        raise SystemExit(f"Campaign report is missing required generated data: {key}")
if int(report.get("canonical_rulers", 0)) < int(report.get("stable_states", 0)):
    raise SystemExit("At least one surviving realm is not bound to a canonical wf-people Character")
if int(report.get("title_hierarchy_links", 0)) <= 0:
    raise SystemExit("Political map has no materialized liege hierarchy")
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
# Reopen the exact same save with the exact same modpack and verify durable world state.
CURRENT_STAGE="save_reload_validation"
: > in.txt
: > server-reload.log
java -Xms1G -Xmx4G -Dworldforge.campaign.acceptance=true \
  -XX:+HeapDumpOnOutOfMemoryError \
  -XX:HeapDumpPath="$JAVA_WORK/reload_java_pid%p.hprof" \
  @user_jvm_args.txt @libraries/net/minecraftforge/forge/$FORGE/win_args.txt nogui \
  < <(tail -n +1 -f in.txt) > server-reload.log 2>&1 &
SERVER_PID=$!
ready=0
for _ in $(seq 1 150); do
  if grep -q 'Done (' server-reload.log 2>/dev/null; then ready=1; break; fi
  if grep -aqE 'Mod loading has failed|Failed to start|Exception in thread "main"|java.lang.Error' server-reload.log 2>/dev/null; then tail -120 server-reload.log; exit 7; fi
  kill -0 "$SERVER_PID" 2>/dev/null || { tail -120 server-reload.log; echo "ERROR: server failed to reopen saved world"; exit 7; }
  sleep 2
done
[ "$ready" = 1 ] || { tail -120 server-reload.log; echo "ERROR: save reload startup timed out"; exit 7; }
printf '%s\n' "wfworldsim campaign status" >> in.txt
if [ "$VANILLA_CAMPAIGN" -eq 1 ]; then printf '%s\n' "wfworldsim geography" >> in.txt; fi
reload_ready=0
for _ in $(seq 1 60); do
  if grep -aqE 'stable_campaign_states=[1-9][0-9]*/[1-9][0-9]*' server-reload.log \
    && grep -aqE 'named_realms=[1-9][0-9]*' server-reload.log \
    && grep -aqE 'canonical_rulers=[1-9][0-9]*' server-reload.log \
    && grep -aqE 'statecraft_titles=[1-9][0-9]*' server-reload.log \
    && grep -aqE 'settlements=[1-9][0-9]*' server-reload.log; then
    if [ "$VANILLA_CAMPAIGN" -eq 0 ] || grep -aqE 'Vanilla geography: regions=[1-9][0-9]*, samples=[1-9][0-9]*' server-reload.log; then
      reload_ready=1; break
    fi
  fi
  kill -0 "$SERVER_PID" 2>/dev/null || { tail -160 server-reload.log; echo "ERROR: saved world stopped during reload validation"; exit 8; }
  sleep 2
done
[ "$reload_ready" = 1 ] || { tail -160 server-reload.log; echo "ERROR: canonical characters/realms/settlements/geography did not survive save reload"; exit 8; }
printf '%s\n' "stop" >> in.txt
for _ in $(seq 1 30); do kill -0 "$SERVER_PID" 2>/dev/null || break; sleep 2; done
kill "$SERVER_PID" 2>/dev/null || true
wait "$SERVER_PID" 2>/dev/null || true
SERVER_PID=""
cp server-reload.log "$OUTPUT/server-reload.log"
echo "WORLD_SAVE_RELOAD=PASS"
