#!/usr/bin/env bash
# Full Forge runtime campaign on either observed vanilla geography or one image-derived map.
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
MAP_SOURCE="${1:?usage: autonomous-campaign.sh <vanilla|worldmap> <addon-jar-dir> <output-dir>}"
JARS="${2:?addon jar directory required}"
OUTPUT="${3:?output directory required}"
MAP_ID="${WF_CAMPAIGN_MAP_ID:-maqan-world}"
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
BOT_PID=""
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
  if [ -n "$BOT_PID" ]; then kill "$BOT_PID" 2>/dev/null || true; wait "$BOT_PID" 2>/dev/null || true; fi
  if [ -f "$WORK/in.txt" ] && [ -n "$SERVER_PID" ]; then
    printf '%s\n' "stop" >> "$WORK/in.txt" 2>/dev/null || true
    for _ in $(seq 1 10); do kill -0 "$SERVER_PID" 2>/dev/null || break; sleep 1; done
    taskkill.exe //PID "$SERVER_PID" //T //F >/dev/null 2>&1 || kill "$SERVER_PID" 2>/dev/null || true
    if wait "$SERVER_PID"; then SERVER_EXIT_CODE=0; else SERVER_EXIT_CODE=$?; fi
    echo "CAMPAIGN_JVM_EXIT_CODE=$SERVER_EXIT_CODE"
  fi
  python3 "$SCRIPT_DIR/campaign-diagnostics.py" --work "$WORK" --output "$OUTPUT" --exit-code "$exit_code" --stage "$CURRENT_STAGE" --run-id "${GITHUB_RUN_ID:-local}" --commit "${GITHUB_SHA:-unknown}" --seed "$MAP_SEED" --map "$MAP_ID" || true
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
  [[ "$MAP_ID" =~ ^[a-z0-9._-]+$ ]] || { echo "ERROR: invalid image map id '$MAP_ID'"; exit 2; }
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
# Geography is the only imported authority. Do not carry image-classified
# realm/state partitions, names, rulers or sovereignty into the running world.
realms = json.loads(files["realms.json"].decode("utf-8"))
if not isinstance(realms, dict) or not isinstance(realms.get("realms"), list):
    raise SystemExit("Map realms.json has an unsupported schema")
authored_realms = len(realms["realms"])
realms["realms"] = []
realms["relations"] = []
files["realms.json"] = json.dumps(realms, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
files.pop("mapitem_realms.bin", None)

titles = json.loads(files["titles.json"].decode("utf-8"))
if not isinstance(titles, dict) or not isinstance(titles.get("tiers"), list) or not isinstance(titles.get("titles"), list):
    raise SystemExit("Map titles.json has an unsupported schema")
source_titles = titles["titles"]
geographic_titles = [row for row in source_titles if isinstance(row, dict) and int(row.get("tier", 99)) in (0, 1)]
if not geographic_titles or not any(int(row.get("tier", 99)) == 0 for row in geographic_titles):
    raise SystemExit("Image map contains no usable province geography")
if not any(int(row.get("tier", 99)) == 1 for row in geographic_titles):
    raise SystemExit("Image map contains no usable region geography")
kept_ids = {int(row["id"]) for row in geographic_titles}
province_count = sum(1 for row in geographic_titles if int(row["tier"]) == 0)
region_count = sum(1 for row in geographic_titles if int(row["tier"]) == 1)
for row in geographic_titles:
    tier = int(row["tier"])
    row["name"] = ""
    row["key"] = ("province_" if tier == 0 else "region_") + str(int(row["id"]))
    if tier == 1:
        row["parent"] = 0
    elif int(row.get("parent", 0)) not in kept_ids:
        raise SystemExit(f"Province {row.get('id')} has no retained geographic region parent")
    row["children"] = []
    row["neighbors"] = [int(value) for value in row.get("neighbors", []) if int(value) in kept_ids]
    props = row.get("props") if isinstance(row.get("props"), dict) else {}
    for key in ("realm", "realm_id", "state", "state_id", "polity", "polity_id", "owner", "owner_id",
                "identity.race", "identity.culture", "race", "culture"):
        props.pop(key, None)
    props["geography_level"] = "province" if tier == 0 else "region"
    row["props"] = props
by_id = {int(row["id"]): row for row in geographic_titles}
for row in geographic_titles:
    parent = int(row.get("parent", 0))
    if parent in by_id:
        by_id[parent]["children"].append(int(row["id"]))
titles["tiers"] = [
    {"index": 0, "key": "province", "name": "Province"},
    {"index": 1, "key": "region", "name": "Region"}
]
titles["titles"] = geographic_titles
files["titles.json"] = json.dumps(titles, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
for name in list(files):
    if name.startswith("mapitem_") and name not in ("mapitem_province.bin", "mapitem_region.bin"):
        files.pop(name, None)

blueprint = json.loads(files["world-blueprint.json"].decode("utf-8")) if "world-blueprint.json" in files else None
if isinstance(blueprint, dict):
    # Keep only geographic candidate sites; the settlement mod chooses actual
    # settlement class, race, culture, government and ownership at runtime.
    blueprint.pop("realms", None)
    blueprint.pop("realm_count", None)
    blueprint.pop("politically_unclaimed_land_pixels", None)
    blueprint.pop("political_land_pixels", None)
    blueprint.pop("generation_policy", None)
    blueprint["province_count"] = province_count
    blueprint["region_count"] = region_count
    for seed in blueprint.get("settlements", []):
        if not isinstance(seed, dict):
            continue
        seed["name"] = ""
        seed["realm_id"] = ""
        seed["type"] = "site"
    files["world-blueprint.json"] = json.dumps(blueprint, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
with zipfile.ZipFile(target, "w", zipfile.ZIP_DEFLATED) as dst:
    for name, data in sorted(files.items()): dst.writestr(name, data)
print("IMAGE_MAP_PREPARED=1")
print(f"AUTHORED_REALMS_REMOVED={authored_realms}")
print(f"GEOGRAPHY_FILES_RETAINED={len(files)}")
PY
  # The map dimension belongs to this save, not to the mod's global built-in datapack.
  # This keeps ordinary vanilla worlds free of phantom/default image maps.
  mkdir -p "world/datapacks/maqan_worldforge/data/worldforge/dimension"
  cat > "world/datapacks/maqan_worldforge/pack.mcmeta" <<'PACK'
{"pack":{"pack_format":15,"description":"MAQAN WorldForge image-derived world dimension"}}
PACK
  python3 - "$MAP_ID" <<'PY'
import json, pathlib, re, sys
map_id = sys.argv[1]
if not re.fullmatch(r"[a-z0-9._-]+", map_id):
    raise SystemExit(f"Invalid WorldForge map resource id: {map_id}")
dimension = {
    "type": "worldforge:mapworld",
    "generator": {"type": "worldforge:image", "map": map_id}
}
target = pathlib.Path("world/datapacks/maqan_worldforge/data/worldforge/dimension") / f"{map_id}.json"
target.write_text(json.dumps(dimension, separators=(",", ":")), encoding="utf-8")
print(f"IMAGE_WORLD_DIMENSION=worldforge:{map_id}")
print("IMAGE_WORLD_DATAPACK_COUNT=1")
PY
else
  # Vanilla means the ordinary overworld only: no image-map dimension or map package.
  rm -rf world/datapacks/maqan_worldforge
  echo "VANILLA_WORLD_SELECTED=1"
  echo "IMAGE_WORLD_DIMENSION_COUNT=0"
fi
echo "eula=true" > eula.txt
cat > server.properties <<P
online-mode=false
level-name=world
level-type=minecraft\:normal
level-seed=$MAP_SEED
generate-structures=true
view-distance=10
simulation-distance=6
spawn-protection=0
enable-command-block=false
max-players=20
server-port=25565
P
: > in.txt
CURRENT_STAGE="forge_startup"
java -Xms2G -Xmx8G \
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
# Acceptance is an ordinary world startup/runtime smoke test.
# No campaign, scenario, goals, time acceleration, or forced outcomes.
if [ "\${WF_ACCEPTANCE_MODE:-}" = "smoke" ]; then
  CURRENT_STAGE="world_runtime_smoke"
  printf '%s\n' "wfworldsim status" "wfworldsim geography" "wfsettle list" "list" >> in.txt
  for second in $(seq 1 30); do
  for second in $(seq 1 30); do
    kill -0 "$SERVER_PID" 2>/dev/null || { tail -160 server.log; echo "ERROR: server exited during ordinary-world runtime smoke"; exit 5; }
    sleep 1
  done
  printf '%s\\n' "stop" >> in.txt
  for _ in $(seq 1 30); do kill -0 "$SERVER_PID" 2>/dev/null || break; sleep 1; done
  if kill -0 "$SERVER_PID" 2>/dev/null; then taskkill.exe //PID "$SERVER_PID" //T //F >/dev/null 2>&1 || kill "$SERVER_PID" 2>/dev/null || true; fi
  if wait "$SERVER_PID"; then SERVER_EXIT_CODE=0; else SERVER_EXIT_CODE=$?; fi
  SERVER_PID=""
  cp server.log "$OUTPUT/server.log"
  [ "$SERVER_EXIT_CODE" = "0" ] || { echo "ERROR: Forge server shutdown returned $SERVER_EXIT_CODE"; exit 5; }
  echo "ORDINARY_WORLD_RUNTIME_SMOKE=PASS"
  echo "NO_CAMPAIGN_OR_SCENARIO=1"
  CURRENT_STAGE="smoke_runtime_complete"
  exit 0
fi

CURRENT_STAGE="geography_preflight"
if [ "$VANILLA_CAMPAIGN" -eq 1 ]; then
  # A real offline Minecraft client is used as a virtual explorer. Teleports are
  # only between survey waypoints; every chunk is requested by the normal player
  # chunk ticket/view-distance pipeline and generated by the vanilla world generator.
  TEST_CHUNKS="${WF_VANILLA_TEST_CHUNKS:-420}"
  [[ "$TEST_CHUNKS" =~ ^[0-9]+$ ]] && [ "$TEST_CHUNKS" -ge 16 ] && [ "$TEST_CHUNKS" -le 420 ] || {
    echo "ERROR: WF_VANILLA_TEST_CHUNKS must be between 16 and 420"; exit 3;
  }
  STEP_CHUNKS=20
  VIEW_RADIUS=10
  GRID_COUNT=$(((TEST_CHUNKS - 1) / STEP_CHUNKS + 1))
  MIN_CHUNK=$(( -(TEST_CHUNKS / 2) ))
  MAX_CHUNK=$(( MIN_CHUNK + TEST_CHUNKS - 1 ))
  echo "VANILLA_TEST_SURVEY_CHUNKS=${TEST_CHUNKS}x${TEST_CHUNKS}"
  echo "VANILLA_TEST_SURVEY_BLOCKS_APPROX=$((TEST_CHUNKS * 16))x$((TEST_CHUNKS * 16))"
  echo "VANILLA_TEST_SURVEY_MODE=real Minecraft player; vanilla player chunk tickets; natural world generation"
  echo "VANILLA_TEST_SURVEY_GRID=${GRID_COUNT}x${GRID_COUNT}; step=${STEP_CHUNKS} chunks; view-distance=${VIEW_RADIUS}"
  BOT_NAME="${WF_SURVEY_USERNAME:-MaqanSurveyor}"
  node "$SCRIPT_DIR/vanilla-survey-bot.js" 25565 "$BOT_NAME" > "$OUTPUT/survey-bot.log" 2>&1 &
  BOT_PID=$!
  bot_ready=0
  for _ in $(seq 1 90); do
    if grep -q "SURVEY_BOT_READY=$BOT_NAME" "$OUTPUT/survey-bot.log" 2>/dev/null; then bot_ready=1; break; fi
    kill -0 "$BOT_PID" 2>/dev/null || { cat "$OUTPUT/survey-bot.log"; echo "ERROR: survey player client exited before joining"; exit 3; }
    sleep 2
  done
  [ "$bot_ready" = 1 ] || { cat "$OUTPUT/survey-bot.log"; echo "ERROR: virtual survey player did not join the Forge server"; exit 3; }
  printf '%s\n' "gamemode creative $BOT_NAME" >> in.txt
  sleep 2
  previous_samples=0
  waypoint=0
  total_waypoints=$((GRID_COUNT * GRID_COUNT))
  for ((zi=0; zi<GRID_COUNT; zi++)); do
    z_chunk=$((MIN_CHUNK + VIEW_RADIUS + zi * STEP_CHUNKS))
    if [ "$z_chunk" -gt $((MAX_CHUNK + VIEW_RADIUS)) ]; then z_chunk=$((MAX_CHUNK + VIEW_RADIUS)); fi
    if (( zi % 2 == 0 )); then x_start=0; x_end=$((GRID_COUNT - 1)); x_step=1
    else x_start=$((GRID_COUNT - 1)); x_end=0; x_step=-1; fi
    for ((xi=x_start; ; xi+=x_step)); do
      x_chunk=$((MIN_CHUNK + VIEW_RADIUS + xi * STEP_CHUNKS))
      if [ "$x_chunk" -gt $((MAX_CHUNK + VIEW_RADIUS)) ]; then x_chunk=$((MAX_CHUNK + VIEW_RADIUS)); fi
      block_x=$((x_chunk * 16 + 8))
      block_z=$((z_chunk * 16 + 8))
      waypoint=$((waypoint + 1))
      echo "VANILLA_SURVEY_WAYPOINT=$waypoint/$total_waypoints CHUNK=$x_chunk,$z_chunk"
      printf 'tp %s %s 250 %s\n' "$BOT_NAME" "$block_x" "$block_z" >> in.txt
      stable=0; captured=0; polls=0
      for _ in $(seq 1 180); do
        printf '%s\n' "wfworldsim geography" >> in.txt
        sleep 2
        polls=$((polls + 1))
        geography_line="$(grep -a 'Vanilla geography:' server.log | tail -1 || true)"
        pending="$(printf '%s' "$geography_line" | sed -nE 's/.*pending_chunks=([0-9]+).*/\1/p')"
        samples="$(printf '%s' "$geography_line" | sed -nE 's/.*samples=([0-9]+).*/\1/p')"
        if [ -n "$pending" ] && [ "$pending" -eq 0 ] && [ -n "$samples" ] && [ "$samples" -ge $((previous_samples + 512)) ] && [ "$polls" -ge 3 ]; then
          stable=$((stable + 1))
          if [ "$stable" -ge 3 ]; then captured=1; previous_samples="$samples"; break; fi
        else
          stable=0
        fi
        kill -0 "$SERVER_PID" 2>/dev/null || { tail -160 server.log; echo "ERROR: vanilla server stopped during player survey"; exit 3; }
        kill -0 "$BOT_PID" 2>/dev/null || { cat "$OUTPUT/survey-bot.log"; echo "ERROR: virtual survey player disconnected"; exit 3; }
      done
      [ "$captured" = 1 ] || { tail -160 server.log; echo "ERROR: player survey waypoint failed to discover new chunks at $x_chunk,$z_chunk; samples=${previous_samples}"; exit 3; }
      if (( xi == x_end )); then break; fi
    done
  done
  printf '%s\n' "wfworldsim geography" >> in.txt
  sleep 3
  geography_line="$(grep -a 'Vanilla geography:' server.log | tail -1 || true)"
  samples="$(printf '%s' "$geography_line" | sed -nE 's/.*samples=([0-9]+).*/\1/p')"
  regions="$(printf '%s' "$geography_line" | sed -nE 's/.*regions=([0-9]+).*/\1/p')"
  expected_samples=$((TEST_CHUNKS * TEST_CHUNKS * 16))
  if [ -z "$samples" ] || [ "$samples" -lt "$expected_samples" ] || [ -z "$regions" ] || [ "$regions" -lt 700 ]; then
    tail -160 server.log
    echo "ERROR: natural player survey incomplete; expected_samples=$expected_samples actual_samples=${samples:-0} regions=${regions:-0}"
    exit 3
  fi
  echo "VANILLA_TEST_SURVEY_CAPTURED=1 CHUNKS=${TEST_CHUNKS}x${TEST_CHUNKS} SAMPLES=$samples REGIONS=$regions"
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
python3 - "$OUTPUT/campaign-report.json" "$OUTPUT/addon-inventory.json" "$JARS" "$MAP_ID" "$VANILLA_CAMPAIGN" <<'PY'
import json, pathlib, sys
report_path, inventory_path, jars_dir = pathlib.Path(sys.argv[1]), pathlib.Path(sys.argv[2]), pathlib.Path(sys.argv[3])
expected_map_id, vanilla_campaign = sys.argv[4], sys.argv[5] == "1"
report = json.loads(report_path.read_text(encoding="utf-8"))
expected_image_maps = 0 if vanilla_campaign else 1
if report.get("map") != expected_map_id:
    raise SystemExit(f"Campaign used the wrong source map: expected={expected_map_id}, actual={report.get('map')}")
if int(report.get("image_map_count", -1)) != expected_image_maps:
    raise SystemExit(f"World source isolation failed: expected image maps={expected_image_maps}, actual={report.get('image_map_count')}")
if int(report.get("preauthored_map_realms", -1)) != 0 or int(report.get("preauthored_state_titles", -1)) != 0:
    raise SystemExit("Image geography contains pre-authored realms or sovereign state titles")
if report.get("map_geography_only") is not True:
    raise SystemExit("Loaded image map exposes political layers instead of neutral geography")
target_generations = int(report.get("target_dynasty_generations", 0))
if report.get("status") != "PASS" or target_generations < 4:
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
for key in ("duplicate_emergent_realm_names", "duplicate_settlement_names", "duplicate_dynasty_names", "duplicate_family_house_names", "duplicate_statecraft_title_names"):
    if int(report.get(key, -1)) != 0:
        raise SystemExit(f"World identity uniqueness check failed: {key}={report.get(key)}")
for key in ("unnamed_emergent_realms", "unnamed_settlements", "unnamed_dynasties", "unnamed_statecraft_titles"):
    if int(report.get(key, -1)) != 0:
        raise SystemExit(f"World identity naming check failed: {key}={report.get(key)}")
race_distribution = report.get("living_population_by_race")
if not isinstance(race_distribution, dict) or not race_distribution:
    raise SystemExit("Campaign report has no living population breakdown by race")
race_total = sum(int(value.get("count", 0)) for value in race_distribution.values())
if race_total != int(report.get("living_population_with_race_stats", -1)):
    raise SystemExit("Living race counts do not reconcile with the reported population total")
if int(report.get("unclassified_living_characters", -1)) != 0:
    raise SystemExit("Some living Characters have no assigned race")
polity_races = report.get("living_population_by_polity_and_race")
if not isinstance(polity_races, dict) or not polity_races:
    raise SystemExit("Campaign report has no per-polity race breakdown")
for polity_id, distribution in polity_races.items():
    if not distribution or abs(sum(float(value.get("share", 0.0)) for value in distribution.values()) - 1.0) > 0.000001:
        raise SystemExit(f"Race shares do not sum to one for polity {polity_id}")
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
for key in ("map", "physical_world_dimension", "elapsed_years", "stable_states", "characters", "settlements", "materialized_settlements", "completed_buildings", "canonical_ruler_generations_verified", "origin_race_characters", "origin_distinct_races", "origin_class_characters", "origin_profession_characters", "living_population_with_race_stats", "unclassified_living_characters", "duplicate_emergent_realm_names", "duplicate_settlement_names", "duplicate_dynasty_names", "duplicate_family_house_names", "duplicate_statecraft_title_names", "unnamed_emergent_realms", "unnamed_settlements", "unnamed_dynasties", "unnamed_statecraft_titles", "world_events", "chronicle_entries"):
    print(f"AUTONOMOUS_CAMPAIGN_{key.upper()}={report.get(key)}")
print(f"AUTONOMOUS_CAMPAIGN_ADDON_JARS={len(inventory)}")
for race, values in sorted(race_distribution.items()):
    print(f"AUTONOMOUS_CAMPAIGN_RACE={race};count={int(values.get('count', 0))};share={float(values.get('share', 0.0)):.6f}")
for polity_id, distribution in sorted(polity_races.items()):
    for race, values in sorted(distribution.items()):
        print(f"AUTONOMOUS_CAMPAIGN_POLITY_RACE={polity_id};race={race};count={int(values.get('count', 0))};share={float(values.get('share', 0.0)):.6f}")
PY
# Reopen the exact same save with the exact same modpack and verify durable world state.
CURRENT_STAGE="save_reload_validation"
: > in.txt
: > server-reload.log
java -Xms2G -Xmx8G \
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
