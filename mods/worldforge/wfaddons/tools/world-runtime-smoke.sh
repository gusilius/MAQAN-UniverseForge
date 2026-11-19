#!/usr/bin/env bash
# Ordinary Forge world startup/runtime smoke; no campaign, goals or scripted outcomes.
# CI accelerates only the shared calendar so autonomous generations can be observed.
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
MAP_SOURCE="${1:?usage: autonomous-runtime.sh <vanilla|worldmap> <addon-jar-dir> <output-dir>}"
JARS="${2:?addon jar directory required}"
OUTPUT="${3:?output directory required}"
MAP_ID="${WF_MAP_ID:-${WF_RUNTIME_MAP_ID:-maqan-world}}"
FORGE="1.20.1-47.3.0"
VANILLA_RUNTIME=0
if [ "$MAP_SOURCE" = "vanilla" ]; then
  VANILLA_RUNTIME=1
  MAP_ID="vanilla"
  MAP_SEED="${WF_VANILLA_SEED:-424242}"
else
  MAP_SEED="$(sha256sum "$MAP_SOURCE" | awk '{print substr($1,1,16)}')"
fi
MAX_RUNTIME_SECONDS="${WF_RUNTIME_TIMEOUT_SECONDS:-3300}"
if [ "$VANILLA_RUNTIME" -eq 0 ]; then MAP_SOURCE="$(cd "$(dirname "$MAP_SOURCE")" && pwd)/$(basename "$MAP_SOURCE")"; fi
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
  echo "RUNTIME_THREAD_DUMP=$stamp"
}
runtime_exit() {
  local exit_code="$1"
  trap - EXIT
  if [ -n "$BOT_PID" ]; then kill "$BOT_PID" 2>/dev/null || true; wait "$BOT_PID" 2>/dev/null || true; fi
  if [ -f "$WORK/in.txt" ] && [ -n "$SERVER_PID" ]; then
    printf '%s\n' "stop" >> "$WORK/in.txt" 2>/dev/null || true
    for _ in $(seq 1 10); do kill -0 "$SERVER_PID" 2>/dev/null || break; sleep 1; done
    taskkill.exe //PID "$SERVER_PID" //T //F >/dev/null 2>&1 || kill "$SERVER_PID" 2>/dev/null || true
    if wait "$SERVER_PID"; then SERVER_EXIT_CODE=0; else SERVER_EXIT_CODE=$?; fi
    echo "RUNTIME_JVM_EXIT_CODE=$SERVER_EXIT_CODE"
  fi
  python3 "$SCRIPT_DIR/world-runtime-diagnostics.py" --work "$WORK" --output "$OUTPUT" --exit-code "$exit_code" --stage "$CURRENT_STAGE" --run-id "${GITHUB_RUN_ID:-local}" --commit "${GITHUB_SHA:-unknown}" --seed "$MAP_SEED" --map "$MAP_ID" || true
  rm -rf "$WORK"
  exit "$exit_code"
}
trap 'runtime_exit $?' EXIT
if [ "$VANILLA_RUNTIME" -eq 0 ]; then [ -f "$MAP_SOURCE" ] || { echo "ERROR: image-derived map package missing: $MAP_SOURCE"; exit 2; }; fi
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
if [ "$VANILLA_RUNTIME" -eq 0 ]; then
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
java -Dwf.simulation.tick-multiplier=100 -Xms2G -Xmx8G \
  -XX:+HeapDumpOnOutOfMemoryError \
  -XX:HeapDumpPath="$JAVA_WORK/java_pid%p.hprof" \
  -XX:ErrorFile="$JAVA_WORK/hs_err_pid%p.log" \
  -Xlog:gc*:file="$JAVA_WORK/gc-%p.log":time,uptime,level,tags \
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
# No campaign runtime, scenario, goals or forced outcomes. The bounded shared-clock
  # multiplier is a test harness setting; every domain system still advances normally.
if [ "${WF_ACCEPTANCE_MODE:-}" = "dual-world" ]; then
  CURRENT_STAGE="same_server_dual_world_genesis"
  # Load real chunks in the ordinary Overworld and the image-backed dimension.
  # These are normal Minecraft world-generation requests, not a campaign or a
  # synthetic data fixture; the mods observe the generated terrain themselves.
  # Sample a bounded 9x9 chunk window (81 chunks) in each dimension.
  # This is enough terrain to test initial civilization bootstrap without
  # pre-generating the planet or force-loading an unbounded world.
  printf '%s\n' "execute in minecraft:overworld run forceload add 0 0 128 128" >> in.txt
  printf 'execute in worldforge:%s run forceload add 0 0 128 128\n' "$MAP_ID" >> in.txt
  printf 'execute in worldforge:%s run say MAQAN_MAPPED_DIMENSION_PRESENT=%s\n' "$MAP_ID" "$MAP_ID" >> in.txt
  printf '%s\n' "wfworldsim status" "wfworldsim geography" "wfsettle list" "list" >> in.txt

  for second in $(seq 1 900); do
    kill -0 "$SERVER_PID" 2>/dev/null || { tail -200 server.log; echo "ERROR: server exited during same-server dual-world genesis"; exit 5; }
    if grep -E 'MAQAN_WORLD_GENESIS source=vanilla-world:minecraft:overworld .*communities=[1-9][0-9]*' server.log \
      && grep -E "MAQAN_WORLD_GENESIS source=$MAP_ID .*communities=[1-9][0-9]*" server.log \
      && grep -Fq 'wf_court: autonomous virtual court attached dimension=minecraft:overworld' server.log \
      && grep -Fq 'wf_relations: autonomous social event kind=friendship' server.log \
      && grep -Fq 'wf_relations: autonomous social event kind=marriage' server.log \
      && grep -Fq 'MAQAN_AUTONOMOUS_BIRTH' server.log \
      && grep -Fq 'wf_reputation: autonomous NPC reputation events=' server.log; then
      break
    fi
    sleep 1
  done

  grep -Fq "MAQAN_MAPPED_DIMENSION_PRESENT=$MAP_ID" server.log || {
    tail -200 server.log
    echo "ERROR: image dimension was not resolved by the same running Minecraft server"
    exit 6
  }
  grep -Fq "MAQAN_MAP_CHUNK_GENERATED map=$MAP_ID" server.log || {
    tail -200 server.log
    echo "ERROR: image dimension did not generate a real chunk through MapChunkGenerator"
    exit 7
  }
  grep -Fq 'MAQAN_WORLD_GENESIS source=vanilla-world:minecraft:overworld' server.log || {
    tail -200 server.log
    echo "ERROR: vanilla Overworld did not produce native WorldGenesis evidence"
    exit 8
  }
  grep -Fq "MAQAN_WORLD_GENESIS source=$MAP_ID" server.log || {
    tail -200 server.log
    echo "ERROR: image dimension did not produce native WorldGenesis evidence"
    exit 9
  }
  grep -Fq 'wf_court: autonomous virtual court attached dimension=minecraft:overworld' server.log || {
    tail -200 server.log
    echo "ERROR: vanilla Overworld has no autonomous virtual court"
    exit 10
  }
  grep -Fq 'wf_relations: autonomous social event kind=friendship' server.log || {
    tail -200 server.log
    echo "ERROR: autonomous NPC friendship produced no events"
    exit 11
  }
  grep -Fq 'wf_relations: autonomous social event kind=marriage' server.log || {
    tail -200 server.log
    echo "ERROR: autonomous NPC marriage produced no events"
    exit 13
  }
  grep -Fq 'MAQAN_AUTONOMOUS_BIRTH' server.log || {
    tail -200 server.log
    echo "ERROR: autonomous court simulation produced no child births"
    exit 14
  }
  grep -Fq 'wf_reputation: autonomous NPC reputation events=' server.log || {
    tail -200 server.log
    echo "ERROR: autonomous NPC events did not update scoped reputation"
    exit 12
  }

  python3 - "$OUTPUT" "$MAP_ID" <<'PY'
import json, pathlib, re, sys
out, map_id = pathlib.Path(sys.argv[1]), sys.argv[2]
log = pathlib.Path("server.log").read_text(encoding="utf-8", errors="replace")
pattern = re.compile(r"MAQAN_WORLD_GENESIS source=(\S+) dimension=(\S+) geography_cells=(\d+) communities=(\d+) settled=(\d+) settlements=(\d+) emergent_settlements=(\d+) map_anchors=(\d+) living_characters=(\d+) named_characters=(\d+) named_settlements=(\d+) court_offices=(\d+) polities=(\d+) named_polities=(\d+) canonical_rulers=(\d+) dynasties=(\d+) titles=(\d+) named_titles=(\d+) governments=(\d+) race_kinds=(\d+) settlement_types=(\{[^}]*\}) government_types=(\{[^}]*\}) vassal_contracts=(\d+) subject_types=(\{[^}]*\}) title_ranks=(\{[^}]*\})")
rows = {}
for match in pattern.finditer(log):
    values = dict(zip(("source","dimension","geography_cells","communities","settled","settlements","emergent_settlements","map_anchors","living_characters","named_characters","named_settlements","court_offices","polities","named_polities","canonical_rulers","dynasties","titles","named_titles","governments","race_kinds"), match.groups()[:20]))
    values["settlement_types"] = json.loads(match.group(21))
    values["government_types"] = json.loads(match.group(22))
    values["vassal_contracts"] = int(match.group(23))
    values["subject_types"] = json.loads(match.group(24))
    values["title_ranks"] = json.loads(match.group(25))
    rows[match.group(1)] = values
expected = {"vanilla-world:minecraft:overworld", map_id}
missing = expected - rows.keys()
if missing:
    raise SystemExit("Missing same-server genesis sources: " + ", ".join(sorted(missing)))
for key in sorted(expected):
    row = rows[key]
    for field in ("geography_cells","communities","settled","settlements","living_characters","court_offices","polities","canonical_rulers","dynasties","titles","governments"):
        if int(row[field]) <= 0:
            raise SystemExit(f"{key}: required native genesis field {field} is {row[field]}")
    if int(row["communities"]) < 4 or int(row["settlements"]) < 4:
        raise SystemExit(f"{key}: initial civilization must contain at least four real communities/settlements")
    if int(row["race_kinds"]) < 2:
        raise SystemExit(f"{key}: expected at least two actual race identities, got {row['race_kinds']}")
    if int(row["governments"]) < int(row["polities"]) or not row["government_types"]:
        raise SystemExit(f"{key}: every emergent polity must have an assigned data-pack government: {row['government_types']}")
    if key == map_id:
        if int(row["polities"]) < 10:
            raise SystemExit(f"{key}: authored world must form a fragmented set of at least ten independent polities, got {row['polities']}")
        if len(row["government_types"]) < 2:
            raise SystemExit(f"{key}: racial/terrain conditions produced only one government form: {row['government_types']}")
        if len(row["settlement_types"]) < 2:
            raise SystemExit(f"{key}: geographic/racial settlement selection produced only one settlement type: {row['settlement_types']}")
        if len(row["title_ranks"]) < 3:
            raise SystemExit(f"{key}: runtime political hierarchy has fewer than three title ranks: {row['title_ranks']}")
    for total, named in (("living_characters","named_characters"), ("settlements","named_settlements"),
                         ("polities","named_polities"), ("titles","named_titles")):
        if int(row[named]) < int(row[total]):
            raise SystemExit(f"{key}: {named}={row[named]} is below {total}={row[total]}; generated objects must receive stable names")
    if int(row["vassal_contracts"]) > 0 and not row["subject_types"]:
        raise SystemExit(f"{key}: vassal contracts exist but no subject types were reported")
    classified = sum(count for kind, count in row["settlement_types"].items() if kind != "unclassified")
    if classified <= 0:
        raise SystemExit(f"{key}: Genesis did not assign any existing data-pack settlement type: {row['settlement_types']}")
    if key == map_id and int(row["map_anchors"]) <= 0:
        raise SystemExit(f"{key}: image-map settlements were not reused/materialized as canonical anchors")
(out / "same-server-world-genesis.json").write_text(json.dumps({key: rows[key] for key in sorted(expected)}, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
print("SAME_SERVER_DUAL_WORLD_GENESIS=PASS")
for key in sorted(expected): print("WORLD_GENESIS=" + json.dumps(rows[key], ensure_ascii=False, sort_keys=True))
PY
  printf '%s\n' "stop" >> in.txt
  for _ in $(seq 1 30); do kill -0 "$SERVER_PID" 2>/dev/null || break; sleep 1; done
  if kill -0 "$SERVER_PID" 2>/dev/null; then taskkill.exe //PID "$SERVER_PID" //T //F >/dev/null 2>&1 || kill "$SERVER_PID" 2>/dev/null || true; fi
  if wait "$SERVER_PID"; then SERVER_EXIT_CODE=0; else SERVER_EXIT_CODE=$?; fi
  SERVER_PID=""
  cp server.log "$OUTPUT/server.log"
  echo "SMOKE_SERVER_SHUTDOWN_CODE=$SERVER_EXIT_CODE"
  echo "SAME_SERVER_DUAL_WORLD_GENESIS=PASS"
  echo "NO_CAMPAIGN_OR_SCENARIO=1"
  CURRENT_STAGE="dual_world_genesis_complete"
  exit 0
fi

echo "ERROR: ordinary-world smoke mode was not selected; refusing scenario execution"
exit 9
