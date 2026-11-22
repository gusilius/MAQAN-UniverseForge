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
TERMINATION_EVIDENCE="not_observed"
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
  echo "RUNTIME_THREAD_DUMP=$stamp PID=$pid"
  # Keep the complete dump as an artifact and print the server plus chunk/IO
  # workers. A server-only excerpt cannot identify the worker side of a
  # CompletableFuture.join() wait in ChunkStorage.
  awk '
    /^"/ {
      printing = ($0 ~ /Server thread|IO-Worker|Worker-Main|Chunk|ForkJoinPool|Worker-[0-9]+/)
      lines = 0
    }
    printing { print; lines++ }
    printing && (lines >= 55 || (lines > 1 && /^$/)) { printing = 0 }
  ' "$OUTPUT/thread-dump-$stamp.txt" | sed 's/^/JVM_THREAD_STACK: /' || true
}
runtime_exit() {
  local exit_code="$1"
  trap - EXIT
  if [ -n "$BOT_PID" ]; then kill "$BOT_PID" 2>/dev/null || true; wait "$BOT_PID" 2>/dev/null || true; fi
  if [ -f "$WORK/in.txt" ] && [ -n "$SERVER_PID" ]; then
    printf '%s\n' "stop" >> "$WORK/in.txt" 2>/dev/null || true
    for _ in $(seq 1 30); do kill -0 "$SERVER_PID" 2>/dev/null || break; sleep 1; done
    if kill -0 "$SERVER_PID" 2>/dev/null; then
      TERMINATION_EVIDENCE="forced_kill_after_grace_timeout"
      taskkill.exe //PID "$SERVER_PID" //T //F >/dev/null 2>&1 || kill "$SERVER_PID" 2>/dev/null || true
    else
      TERMINATION_EVIDENCE="graceful_stop"
    fi
    if wait "$SERVER_PID"; then SERVER_EXIT_CODE=0; else SERVER_EXIT_CODE=$?; fi
    echo "RUNTIME_JVM_EXIT_CODE=$SERVER_EXIT_CODE"
    echo "RUNTIME_TERMINATION_EVIDENCE=$TERMINATION_EVIDENCE"
  fi
  if [ "$exit_code" -eq 0 ] && { [ "$SERVER_EXIT_CODE" != "0" ] || [ "$TERMINATION_EVIDENCE" != "graceful_stop" ]; }; then
    echo "ERROR: runtime scenario cannot PASS without observed JVM exit 0 and graceful shutdown"
    exit_code=14
  fi
  python3 "$SCRIPT_DIR/world-runtime-diagnostics.py" --work "$WORK" --output "$OUTPUT" --exit-code "$exit_code" --server-exit-code "$SERVER_EXIT_CODE" --termination "$TERMINATION_EVIDENCE" --stage "$CURRENT_STAGE" --run-id "${GITHUB_RUN_ID:-local}" --commit "${GITHUB_SHA:-unknown}" --seed "$MAP_SEED" --map "$MAP_ID" || true
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
required = {"meta.json", "biomes.png", "height16.png", "provinces.png", "titles.json", "realms.json", "world-blueprint.json"}
with zipfile.ZipFile(source, "r") as src:
    names = [n for n in src.namelist() if not n.endswith("/")]
    basenames = {}
    for name in names:
        base = pathlib.PurePosixPath(name).name
        if base in basenames:
            raise SystemExit(f"Ambiguous map package entry: {base}")
        basenames[base] = name
    missing = required - basenames.keys()
    if missing:
        raise SystemExit("Map package lacks required files: " + ", ".join(sorted(missing)))
    files = {base: src.read(name) for base, name in basenames.items()}
realms = json.loads(files["realms.json"].decode("utf-8"))
if not isinstance(realms, dict) or not isinstance(realms.get("realms"), list):
    raise SystemExit("Map realms.json has an unsupported schema")
if realms["realms"]:
    raise SystemExit("Image map must provide unowned state-formation boundaries, not preassigned sovereigns")
titles = json.loads(files["titles.json"].decode("utf-8"))
blueprint = json.loads(files["world-blueprint.json"].decode("utf-8"))
if not isinstance(titles, dict) or not isinstance(titles.get("tiers"), list) or not isinstance(titles.get("titles"), list):
    raise SystemExit("Map titles.json has an unsupported schema")
tier_ids = [int(row["index"]) for row in titles["tiers"]]
if tier_ids != [0, 1, 2]:
    raise SystemExit(f"Political/geographic hierarchy was lost: expected province/region/state tiers, got {tier_ids}")
rows = titles["titles"]
counts = {tier: sum(1 for row in rows if int(row["tier"]) == tier) for tier in tier_ids}
expected = {0: int(blueprint["province_count"]), 1: int(blueprint["region_count"]),
            2: int(blueprint["state_candidate_count"])}
if counts != expected:
    raise SystemExit(f"Territorial hierarchy count mismatch: actual={counts}, expected={expected}")
by_id = {int(row["id"]): row for row in rows}
for row in rows:
    tier, parent = int(row["tier"]), int(row["parent"])
    expected_parent_tier = {0: 1, 1: 2}.get(tier)
    if expected_parent_tier is None:
        if parent != 0:
            raise SystemExit(f"State candidate must be a root title: {row.get('key')}")
    elif parent not in by_id or int(by_id[parent]["tier"]) != expected_parent_tier:
        raise SystemExit(f"Territorial parent link was lost: {row.get('key')}")
sites = blueprint.get("settlements", [])
if len(sites) != int(blueprint["settlement_count"]) or len(sites) < int(blueprint["province_count"]):
    raise SystemExit("Settlement opportunity count is incomplete")
for site in sites:
    if site.get("name") or site.get("realm_id") or site.get("type") != "site":
        raise SystemExit(f"Settlement opportunity must remain unowned and unnamed: {site.get('id')}")
with zipfile.ZipFile(target, "w", zipfile.ZIP_DEFLATED) as dst:
    for name, data in sorted(files.items()):
        dst.writestr(name, data)
print("IMAGE_MAP_PREPARED=1")
print("POLITICAL_BOUNDARIES_PRESERVED=1")
print(f"STATE_FORMATION_CANDIDATES={counts[2]}")
print(f"SETTLEMENT_OPPORTUNITIES={len(sites)}")
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
online-mode=true
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
java -Dwf.simulation.tick-multiplier=${WF_SIMULATION_TICK_MULTIPLIER:-1} -Xms1G -Xmx3G \
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
  # Touch only one chunk in each dimension. The previous 9x9 tickets caused
  # hundreds of chunks to remain active in a test whose purpose is lazy activation.
  printf '%s\n' "execute in minecraft:overworld run forceload add 0 0 0 0" >> in.txt
  printf 'execute in worldforge:%s run forceload add 0 0 0 0\n' "$MAP_ID" >> in.txt
  printf 'execute in worldforge:%s run say MAQAN_MAPPED_DIMENSION_PRESENT=%s\n' "$MAP_ID" "$MAP_ID" >> in.txt
  printf '%s\n' "wfworldsim status" "wfworldsim geography" "wfsettle list" "list" >> in.txt

  FORCELoad_RELEASED=0
  RUNTIME_START_EPOCH="$(date +%s)"
  RUNTIME_TIMEOUT_REACHED=0
  for second in $(seq 1 "$MAX_RUNTIME_SECONDS"); do
    # Preserve periodic JVM snapshots while the server is healthy. The watchdog's
    # own crash report did not contain the main Server thread in the last run, so
    # a time series is required to identify the last live call stack before a stall.
    if (( second == 1 || second % 15 == 0 )); then capture_server_threads; fi
    # Release both one-chunk tickets as soon as the target chunks have been generated.
    # The rest of the world must remain dormant and must not be simulated by this test.
    if (( FORCELoad_RELEASED == 0 )) \
      && grep -Fq "MAQAN_MAP_CHUNK_GENERATED map=$MAP_ID" server.log \
      && grep -Fq 'MAQAN_WORLD_GENESIS source=vanilla-world:minecraft:overworld' server.log; then
      printf '%s\n' "execute in minecraft:overworld run forceload remove 0 0 0 0" >> in.txt
      printf 'execute in worldforge:%s run forceload remove 0 0 0 0\n' "$MAP_ID" >> in.txt
      FORCELoad_RELEASED=1
      echo "RUNTIME_TEST_CHUNK_TICKETS_RELEASED=1"
    fi
    kill -0 "$SERVER_PID" 2>/dev/null || { tail -200 server.log; echo "ERROR: server exited during same-server dual-world genesis"; exit 5; }
    if grep -E 'MAQAN_WORLD_GENESIS source=vanilla-world:minecraft:overworld .*communities=[1-9][0-9]*' server.log \
      && grep -Fq 'wf_court: autonomous virtual court attached dimension=minecraft:overworld' server.log \
      && grep -Fq 'wf_relations: autonomous NPC interactions=' server.log \
      && grep -Fq 'wf_reputation: autonomous NPC reputation events=' server.log; then
      break
    fi
    sleep 1
  done
  if ! (grep -E 'MAQAN_WORLD_GENESIS source=vanilla-world:minecraft:overworld .*communities=[1-9][0-9]*' server.log \
    && grep -Fq 'wf_court: autonomous virtual court attached dimension=minecraft:overworld' server.log \
    && grep -Fq 'wf_relations: autonomous NPC interactions=' server.log \
    && grep -Fq 'wf_reputation: autonomous NPC reputation events=' server.log); then
    RUNTIME_TIMEOUT_REACHED=1
    echo "RUNTIME_SCENARIO_TIMEOUT_SECONDS=$MAX_RUNTIME_SECONDS"
    echo "ERROR: dual-world runtime did not reach acceptance evidence within its configured timeout"
    exit 13
  fi

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
  if grep -Fq "MAQAN_WORLD_GENESIS source=$MAP_ID" server.log; then
    echo "ERROR: unvisited image dimension was eagerly activated; lazy activation invariant violated"
    exit 9
  fi
  echo "UNVISITED_IMAGE_DIMENSION_REMAINS_DORMANT=PASS"
  grep -Fq 'wf_court: autonomous virtual court attached dimension=minecraft:overworld' server.log || {
    tail -200 server.log
    echo "ERROR: vanilla Overworld has no autonomous virtual court"
    exit 10
  }
  grep -Fq 'wf_relations: autonomous NPC interactions=' server.log || {
    tail -200 server.log
    echo "ERROR: autonomous NPC social simulation produced no interactions"
    exit 11
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
pattern = re.compile(r"MAQAN_WORLD_GENESIS source=(?P<source>\S+) dimension=(?P<dimension>\S+) geography_cells=(?P<geography_cells>\d+) communities=(?P<communities>\d+) settled=(?P<settled>\d+) settlements=(?P<settlements>\d+) colony_records=(?P<colony_records>\d+) emergent_settlements=(?P<emergent_settlements>\d+) blueprint_sites=(?P<map_anchors>\d+) empty_blueprint_sites=(?P<empty_blueprint_sites>\d+) active_settlements=(?P<active_settlements>\d+) living_characters=(?P<living_characters>\d+) named_characters=(?P<named_characters>\d+) named_settlements=(?P<named_settlements>\d+) court_offices=(?P<court_offices>\d+) polities=(?P<polities>\d+) named_polities=(?P<named_polities>\d+) canonical_rulers=(?P<canonical_rulers>\d+) dynasties=(?P<dynasties>\d+) titles=(?P<titles>\d+) named_titles=(?P<named_titles>\d+) governments=(?P<governments>\d+) race_kinds=(?P<race_kinds>\d+) settlement_types=(?P<settlement_types>\{[^}]*\}) blueprint_site_types=(?P<blueprint_site_types>\{[^}]*\}) government_types=(?P<government_types>\{[^}]*\}) duplicate_centers=(?P<duplicate_centers>\d+) nearby_center_conflicts=(?P<nearby_center_conflicts>\d+) vassal_contracts=(?P<vassal_contracts>\d+) subject_types=(?P<subject_types>\{[^}]*\}) title_ranks=(?P<title_ranks>\{[^}]*\})")
rows = {}
for match in pattern.finditer(log):
    values = match.groupdict()
    for field in ("geography_cells", "communities", "settled", "settlements", "colony_records",
                  "emergent_settlements", "map_anchors", "empty_blueprint_sites", "active_settlements",
                  "living_characters", "named_characters", "named_settlements", "court_offices",
                  "polities", "named_polities", "canonical_rulers", "dynasties", "titles",
                  "named_titles", "governments", "race_kinds", "duplicate_centers",
                  "nearby_center_conflicts", "vassal_contracts"):
        values[field] = int(values[field])
    for field in ("settlement_types", "blueprint_site_types", "government_types", "subject_types", "title_ranks"):
        values[field] = json.loads(values[field])
    rows[values["source"]] = values
expected = {"vanilla-world:minecraft:overworld"}
if re.search(rf"MAQAN_WORLD_GENESIS source={re.escape(map_id)}\b", log):
    raise SystemExit("Unvisited image-backed dimension was eagerly activated; Genesis must be visit-triggered")
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
