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
run_bounded() {
  local seconds="$1"
  shift
  python3 - "$seconds" "$@" <<'PY'
import subprocess, sys
limit = float(sys.argv[1])
command = sys.argv[2:]
try:
    completed = subprocess.run(command, timeout=limit)
except subprocess.TimeoutExpired:
    print("BOUNDED_COMMAND_TIMEOUT seconds=%s command=%r" % (limit, command), file=sys.stderr)
    raise SystemExit(124)
raise SystemExit(completed.returncode)
PY
}

wait_for_process_exit() {
  local pid="$1"
  local seconds="$2"
  local elapsed
  for ((elapsed=0; elapsed<seconds; elapsed++)); do
    kill -0 "$pid" 2>/dev/null || return 0
    sleep 1
  done
  ! kill -0 "$pid" 2>/dev/null
}

capture_server_threads() {
  local stamp pid
  stamp="$(date +%s)"
  run_bounded 8 jcmd -l > "$OUTPUT/jcmd-processes-$stamp.txt" 2>&1 || {
    echo "RUNTIME_DIAGNOSTIC_JCMD_LIST_FAILED_OR_TIMED_OUT=$stamp"
    return 0
  }
  pid="$(awk '/BootstrapLauncher|net.minecraft.server.Main|MinecraftServer/ {print $1; exit}' "$OUTPUT/jcmd-processes-$stamp.txt")"
  [ -n "$pid" ] || return 0
  run_bounded 12 jcmd "$pid" Thread.print -l > "$OUTPUT/thread-dump-$stamp.txt" 2>&1 || {
    echo "RUNTIME_DIAGNOSTIC_THREAD_DUMP_FAILED_OR_TIMED_OUT=$stamp PID=$pid"
  }
  run_bounded 8 jcmd "$pid" GC.heap_info > "$OUTPUT/runtime-heap-$stamp.txt" 2>&1 || {
    echo "RUNTIME_DIAGNOSTIC_HEAP_FAILED_OR_TIMED_OUT=$stamp PID=$pid"
  }
  WF_METRIC_PID="$pid" run_bounded 10 powershell.exe -NoProfile -Command '$p=Get-Process -Id ([int]$env:WF_METRIC_PID) -ErrorAction Stop; [pscustomobject]@{pid=$p.Id;cpu_seconds=$p.CPU;working_set_bytes=$p.WorkingSet64;private_memory_bytes=$p.PrivateMemorySize64;threads=$p.Threads.Count;handles=$p.HandleCount;sampled_at_utc=[DateTime]::UtcNow.ToString("o")} | ConvertTo-Json -Compress' > "$OUTPUT/runtime-process-$stamp.json" 2>&1 || true
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
  if [ -n "$BOT_PID" ]; then
    kill "$BOT_PID" 2>/dev/null || true
    if ! wait_for_process_exit "$BOT_PID" 5; then
      echo "RUNTIME_BOT_GRACE_TIMEOUT=5"
      kill -KILL "$BOT_PID" 2>/dev/null || true
    fi
    if wait_for_process_exit "$BOT_PID" 5; then
      wait "$BOT_PID" 2>/dev/null || true
    else
      echo "RUNTIME_BOT_REAP_TIMEOUT=5"
    fi
  fi
  if [ -f "$WORK/in.txt" ] && [ -n "$SERVER_PID" ]; then
    CURRENT_STAGE="server_shutdown"
    echo "RUNTIME_SHUTDOWN_BEGIN_STAGE=$CURRENT_STAGE"
    printf '%s\n' "stop" >> "$WORK/in.txt" 2>/dev/null || true
    # Large acceptance worlds may need a full snapshot pass at stop. Keep the
    # shutdown graceful and observable; do not turn a slow save into a forced kill.
    for second in $(seq 1 120); do
      kill -0 "$SERVER_PID" 2>/dev/null || break
      if (( second % 15 == 0 )); then
        echo "RUNTIME_SHUTDOWN_DIAGNOSTIC_SECONDS=$second"
        capture_server_threads
        local stamp pid
        stamp="$(date +%s)"
        run_bounded 8 jcmd -l > "$OUTPUT/jcmd-processes-shutdown-$stamp.txt" 2>&1 || {
          echo "RUNTIME_SHUTDOWN_JCMD_LIST_FAILED_OR_TIMED_OUT=$stamp"
        }
        pid="$(awk '/BootstrapLauncher|net.minecraft.server.Main|MinecraftServer/ {print $1; exit}' "$OUTPUT/jcmd-processes-shutdown-$stamp.txt")"
        if [ -n "$pid" ]; then
          run_bounded 8 jcmd "$pid" GC.heap_info > "$OUTPUT/shutdown-heap-$stamp.txt" 2>&1 || {
            echo "RUNTIME_SHUTDOWN_HEAP_FAILED_OR_TIMED_OUT=$stamp PID=$pid"
          }
        fi
      fi
      sleep 1
    done
    if kill -0 "$SERVER_PID" 2>/dev/null; then
      echo "RUNTIME_SHUTDOWN_GRACE_TIMEOUT=120"
      capture_server_threads
      TERMINATION_EVIDENCE="forced_kill_after_grace_timeout"
      run_bounded 12 taskkill.exe //PID "$SERVER_PID" //T //F >/dev/null 2>&1 || true
      kill -TERM "$SERVER_PID" 2>/dev/null || true
      if ! wait_for_process_exit "$SERVER_PID" 5; then
        kill -KILL "$SERVER_PID" 2>/dev/null || true
      fi
      if ! wait_for_process_exit "$SERVER_PID" 10; then
        SERVER_EXIT_CODE="not_reaped_after_forced_kill"
        echo "RUNTIME_PROCESS_REAP_TIMEOUT=10"
      fi
    else
      TERMINATION_EVIDENCE="graceful_stop"
    fi
    # wait is safe only after the process is no longer running. Never let the
    # cleanup trap block indefinitely after taskkill or a failed JVM shutdown.
    if [ "$SERVER_EXIT_CODE" = "not_observed" ] && ! kill -0 "$SERVER_PID" 2>/dev/null; then
      if wait "$SERVER_PID"; then SERVER_EXIT_CODE=0; else SERVER_EXIT_CODE=$?; fi
    fi
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
java -Dwf.stress.enabled=${WF_STRESS_ENABLED:-false} -Dwf.social.stress.enabled=$([[ "${WF_ACCEPTANCE_MODE:-}" = "social-stress" ]] && echo true || echo false) -Dwf.acceptance.contour02=$([[ "${WF_ACCEPTANCE_MODE:-}" = "population-stress" ]] && echo true || echo false) -Dwf.settlement.stress.enabled=$([[ "${WF_ACCEPTANCE_MODE:-}" = "settlement-stress" ]] && echo true || echo false) -Dwf.simulation.tick-multiplier=${WF_SIMULATION_TICK_MULTIPLIER:-1} -Xms1G -Xmx3G \
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
if [ "${WF_ACCEPTANCE_MODE:-}" = "settlement-stress" ]; then
  CURRENT_STAGE="settlement_labor_stress"
  TARGET="${WF_SETTLEMENT_STRESS_TARGET:-250}"
  REPORT="world/worldforge/stresslab/contour-04-settlements.json"
  echo "SETTLEMENT_STRESS_CONTOUR=04"
  echo "SETTLEMENT_STRESS_REMOTE_COLONIES=$TARGET"
  printf 'wfsettle-stress start %s\n' "$TARGET" >> in.txt
  SETTLEMENT_REPORT_READY=0
  for second in $(seq 1 "$MAX_RUNTIME_SECONDS"); do
    if (( second == 1 || second % 15 == 0 )); then capture_server_threads; fi
    if (( second % 15 == 0 )); then
      echo "SETTLEMENT_STRESS_ELAPSED_SECONDS=$second"
      grep 'WF_STRESS contour=04_settlements' server.log | tail -5 || true
    fi
    if grep -Fq "WF_STRESS_CONTOUR04_SETUP_FAILED" server.log; then
      echo "ERROR: contour 04 command rejected its setup; refusing to wait for a report that cannot be produced"
      grep -F "WF_STRESS_CONTOUR04_SETUP_FAILED" server.log | tail -20 || true
      tail -120 server.log
      exit 54
    fi
    if [ -f "$REPORT" ]; then
      cp "$REPORT" "$OUTPUT/contour-04-settlements.json"
      SETTLEMENT_STATUS="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1],encoding="utf-8")).get("status","INVALID"))' "$REPORT")"
      echo "SETTLEMENT_STRESS_REPORTED_STATUS=$SETTLEMENT_STATUS"
      SETTLEMENT_REPORT_READY=1
      break
    fi
    kill -0 "$SERVER_PID" 2>/dev/null || { tail -200 server.log; echo "ERROR: Forge server exited during settlement/labor stress"; exit 51; }
    sleep 1
  done
  if [ "$SETTLEMENT_REPORT_READY" -ne 1 ]; then
    echo "SETTLEMENT_STRESS_TIMEOUT_STAGE=$CURRENT_STAGE"
    grep 'WF_STRESS contour=04_settlements' server.log | tail -80 || true
    exit 52
  fi
  python3 - "$REPORT" "$TARGET" <<'PY'
import json, pathlib, sys
report = json.loads(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8"))
target = int(sys.argv[2])
checks = {
    "status": report.get("status") == "PASS",
    "runtime_kind": report.get("runtime_kind") == "minecraft_forge_dedicated_server_settlement_stress",
    "dedicated_server": report.get("dedicated_server") is True,
    "mods_loaded": report.get("required_mods_loaded") is True,
    "remote_colonies": report.get("created_remote_colonies") == target,
    "local_workers": report.get("local_workers", 0) >= 4,
    "work_orders": report.get("local_work_orders") == report.get("local_workers"),
    "physical_wood_harvest": report.get("wood_produced_by_physical_workers", 0) > 0,
    "terrain_samples": report.get("terrain_cells_captured_from_loaded_world", 0) > 0,
    "save_load": report.get("work_order_save_load_round_trip") is True,
    "durable_snapshot": report.get("durable_settlement_snapshot") is True,
    "canonical_worker_identity": report.get("canonical_worker_identity_verified") is True
        and report.get("canonical_worker_character_links") == report.get("local_workers"),
    "canonical_worker_durable_writes": report.get("canonical_worker_durable_writes_verified") is True
        and report.get("canonical_worker_durable_writes") == report.get("local_workers"),
    "physical_lod": report.get("physical_lod_verified") is True,
    "near_bodies": report.get("near_player_worker_bodies", 0) >= 4,
    "far_bodies": report.get("far_player_worker_bodies") == 0,
    "return_bodies": report.get("return_player_worker_bodies", 0) >= 4,
    "navigation": report.get("worker_navigation_active_samples", 0) > 0,
    "remote_chunks_dormant": report.get("remote_settlement_chunks_loaded") == 0,
    "ticks": report.get("measured_server_ticks", 0) >= 400,
    "tps": report.get("server_tps", 0) >= 15,
    "tick_p95": report.get("server_tick_ms_p95", 1e9) <= 50,
    "tick_p99": report.get("server_tick_ms_p99", 1e9) <= 100,
    "tick_max": report.get("server_tick_ms_max", 1e9) <= 1000,
}
for key, passed in checks.items():
    print(f"SETTLEMENT_STRESS_CHECK_{key.upper()}={'PASS' if passed else 'FAIL'}")
if not all(checks.values()):
    raise SystemExit("Contour 04 evidence failed: " + repr(report))
print("SETTLEMENT_STRESS_EVIDENCE=PASS")
print(json.dumps(report, ensure_ascii=False, sort_keys=True))
PY
  printf '%s\n' "save-all flush" >> in.txt
  for _ in $(seq 1 30); do grep -Fq 'Saved the game' server.log && break; sleep 1; done
  grep -Fq 'Saved the game' server.log || { echo "ERROR: contour 04 save-all flush was not acknowledged"; exit 53; }
  echo "SETTLEMENT_STRESS_CONTOUR_RESULT=PASS_PENDING_GRACEFUL_SHUTDOWN"
  CURRENT_STAGE="settlement_labor_stress_complete"
  exit 0
fi

if [ "${WF_ACCEPTANCE_MODE:-}" = "social-stress" ]; then
  CURRENT_STAGE="social_life_stress"
  TARGET="${WF_SOCIAL_STRESS_TARGET:-100000}"
  DAYS="${WF_SOCIAL_STRESS_DAYS:-635}"
  REPORT="world/worldforge/stresslab/contour-03-social.json"
  echo "SOCIAL_STRESS_CONTOUR=03"
  echo "SOCIAL_STRESS_TARGET=$TARGET"
  echo "SOCIAL_STRESS_DAYS=$DAYS"
  printf 'wfrelstress start %s %s\n' "$TARGET" "$DAYS" >> in.txt
  SOCIAL_REPORT_READY=0
  for second in $(seq 1 "$MAX_RUNTIME_SECONDS"); do
    if (( second == 1 || second % 15 == 0 )); then capture_server_threads; fi
    if (( second % 60 == 0 )); then
      echo "SOCIAL_STRESS_ELAPSED_SECONDS=$second"
      grep 'WF_STRESS contour=03_social' server.log | tail -4 || true
    fi
    if [ -f "$REPORT" ]; then
      cp "$REPORT" "$OUTPUT/contour-03-social.json"
      SOCIAL_STATUS="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1],encoding="utf-8")).get("status","INVALID"))' "$REPORT")"
      echo "SOCIAL_STRESS_REPORTED_STATUS=$SOCIAL_STATUS"
      SOCIAL_REPORT_READY=1
      break
    fi
    kill -0 "$SERVER_PID" 2>/dev/null || { tail -200 server.log; echo "ERROR: Forge server exited during social-life stress"; exit 41; }
    sleep 1
  done
  if [ "$SOCIAL_REPORT_READY" -ne 1 ]; then
    echo "SOCIAL_STRESS_TIMEOUT_STAGE=$CURRENT_STAGE"
    grep 'WF_STRESS contour=03_social' server.log | tail -60 || true
    printf '%s\n' "wfrelstress status" "stop" >> in.txt
    exit 42
  fi
  if [ "$SOCIAL_STATUS" != "PASS" ]; then
    cat "$REPORT"
    echo "ERROR: contour 03 autonomous social simulation did not satisfy lifecycle invariants (status=$SOCIAL_STATUS)"
    exit 43
  fi
  python3 - "$REPORT" "$TARGET" "$DAYS" <<'PY'
import json, pathlib, sys
report = json.loads(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8"))
target, days = int(sys.argv[2]), int(sys.argv[3])
checks = {
    "status": report.get("status") == "PASS",
    "runtime_kind": report.get("runtime_kind") == "dedicated_server_production_domain_api_stress",
    "dedicated_server": report.get("dedicated_server") is True,
    "required_mods_loaded": report.get("required_mods_loaded") is True,
    "canonical_registry_population": report.get("canonical_registry_population") is True,
    "demographic_fixture_disclosed": report.get("demographic_fixture_seeded_marriages") is True and report.get("fixture_married_couples", 0) > 0,
    "physical_entity_scope_explicit": report.get("physical_npc_entity_spawn_verified") is False,
    "server_restart_scope_explicit": report.get("server_restart_persistence_verified") is False,
    "created": report.get("created") == target,
    "days": report.get("days_completed") == days,
    "interactions": report.get("autonomous_interactions", 0) > 0,
    "bonds": report.get("npc_directed_bonds", 0) > 0,
    "reciprocal": report.get("reciprocal_bonds") == report.get("npc_directed_bonds"),
    "marriages": report.get("married_directed_bonds", 0) > 0,
    "conceptions": report.get("conceptions", 0) > 0,
    "births": report.get("births", 0) > 0 and report.get("births") == report.get("conceptions"),
    "deaths": report.get("deaths", 0) > 0,
    "pregnancies_drained": report.get("pending_pregnancies", -1) == 0,
    "family_links_present": report.get("family_links", 0) > 0,
    "family_integrity": report.get("family_integrity_errors", -1) == 0,
    "family_integrity_after_reload": report.get("restored_family_integrity_errors", -1) == 0,
    "canonical_family_save_load": report.get("family_save_load_verified") is True,
    "relations_save_load": report.get("relations_save_load_verified") is True,
    "registry": report.get("final_registry_size", 0) >= report.get("initial_registry_size", 0) + target + report.get("births", 0),
}
for key, passed in checks.items():
    print(f"SOCIAL_STRESS_CHECK_{key.upper()}={'PASS' if passed else 'FAIL'}")
if not all(checks.values()):
    raise SystemExit("Contour 03 evidence failed: " + repr(report))
print("SOCIAL_STRESS_EVIDENCE=PASS")
print(json.dumps(report, ensure_ascii=False, sort_keys=True))
PY
  printf '%s\n' "save-all flush" >> in.txt
  for _ in $(seq 1 30); do grep -Fq 'Saved the game' server.log && break; sleep 1; done
  grep -Fq 'Saved the game' server.log || { echo "ERROR: contour 03 save-all flush was not acknowledged"; exit 44; }
  echo "SOCIAL_STRESS_CONTOUR_RESULT=PASS_PENDING_GRACEFUL_SHUTDOWN"
  CURRENT_STAGE="social_life_stress_complete"
  exit 0
fi

if [ "${WF_ACCEPTANCE_MODE:-}" = "population-stress" ]; then
  CURRENT_STAGE="population_stress"
  TARGET="${WF_STRESS_TARGET:-1000000}"
  BATCH="${WF_STRESS_BATCH:-128}"
  REPORT="world/worldforge/stresslab/contour-02-population.json"
  echo "POPULATION_STRESS_CONTOUR=02"
  echo "POPULATION_STRESS_TARGET=$TARGET"
  echo "POPULATION_STRESS_BATCH_PER_TICK=$BATCH"
  printf 'wfstress population start %s %s\n' "$TARGET" "$BATCH" >> in.txt
  STRESS_REPORT_READY=0
  for second in $(seq 1 "$MAX_RUNTIME_SECONDS"); do
    if (( second == 1 || second % 15 == 0 )); then capture_server_threads; fi
    if (( second % 60 == 0 )); then
      echo "POPULATION_STRESS_ELAPSED_SECONDS=$second"
      grep 'WF_STRESS contour=02_population progress' server.log | tail -3 || true
    fi
    if [ -f "$REPORT" ]; then
      cp "$REPORT" "$OUTPUT/contour-02-population.json"
      STRESS_STATUS="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1],encoding="utf-8")).get("status","INVALID"))' "$REPORT")"
      echo "POPULATION_STRESS_REPORTED_STATUS=$STRESS_STATUS"
      STRESS_REPORT_READY=1
      break
    fi
    kill -0 "$SERVER_PID" 2>/dev/null || { tail -200 server.log; echo "ERROR: Forge server exited during in-game population stress"; exit 31; }
    sleep 1
  done
  if [ "$STRESS_REPORT_READY" -ne 1 ]; then
    echo "POPULATION_STRESS_TIMEOUT_STAGE=$CURRENT_STAGE"
    grep 'WF_STRESS contour=02_population' server.log | tail -40 || true
    printf '%s\n' "wfstress population status" "wfstress population stop" >> in.txt
    for _ in $(seq 1 20); do
      if [ -f "$REPORT" ]; then
        cp "$REPORT" "$OUTPUT/contour-02-population-timeout.json"
        echo "POPULATION_STRESS_TIMEOUT_REPORT_CAPTURED=1"
        cat "$REPORT"
        break
      fi
      sleep 1
    done
    echo "ERROR: in-game population stress did not produce its evidence report within $MAX_RUNTIME_SECONDS seconds"
    exit 32
  fi
  if [ "$STRESS_STATUS" != "PASS" ]; then
    echo "ERROR: production population simulation receipts did not all reach the verification world day (status=$STRESS_STATUS)"
    exit 33
  fi
  python3 - "$REPORT" "$OUTPUT/contour-02-population.json" "$TARGET" <<'PY'
import json, pathlib, sys
report = json.loads(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8"))
target = int(sys.argv[3])
if report.get("status") != "PASS":
    raise SystemExit("Population contour did not pass: " + str(report.get("status")))
if report.get("created") != target or report.get("final_registry_size", 0) < report.get("initial_registry_size", 0) + target:
    raise SystemExit("Canonical Character count does not match the requested workload")
if report.get("durable_character_writes") != target:
    raise SystemExit("Not every created Character reached the production durable write API")
if report.get("simulation_processed") != target or report.get("simulation_pending") != 0:
    raise SystemExit("Normal population tick simulation did not process every generated Character")
print("POPULATION_STRESS_EVIDENCE=PASS")
print(json.dumps(report, ensure_ascii=False, sort_keys=True))
PY
  if [ "$?" -ne 0 ]; then exit 34; fi
  printf '%s\n' "save-all flush" >> in.txt
  for _ in $(seq 1 30); do grep -Fq 'Saved the game' server.log && break; sleep 1; done
  grep -Fq 'Saved the game' server.log || { echo "ERROR: population stress save-all flush was not acknowledged"; exit 35; }
  echo "POPULATION_STRESS_CONTOUR_RESULT=PASS_PENDING_GRACEFUL_SHUTDOWN"
  CURRENT_STAGE="population_stress_complete"
  exit 0
fi

if [ "${WF_ACCEPTANCE_MODE:-}" = "server-core" ]; then
  CURRENT_STAGE="server_core_first_run"
  echo "SERVER_CORE_CONTOUR=00"
  echo "SERVER_CORE_PHASE=first_start"
  # Exercise the ordinary server lifecycle without loading an image dimension,
  # forcing chunks, or invoking population/worldgen acceptance scenarios.
  printf '%s\n' "list" "save-all flush" >> in.txt
  for second in $(seq 1 "${WF_CORE_OBSERVE_SECONDS:-120}"); do
    if (( second == 1 || second % 15 == 0 )); then
      capture_server_threads
      stamp="$(date +%s)"
      jcmd -l > "$OUTPUT/core-jcmd-$stamp.txt" 2>&1 || true
      pid="$(awk '/BootstrapLauncher|net.minecraft.server.Main|MinecraftServer/ {print $1; exit}' "$OUTPUT/core-jcmd-$stamp.txt")"
      if [ -n "$pid" ]; then
        jcmd "$pid" GC.heap_info > "$OUTPUT/core-heap-$stamp.txt" 2>&1 || true
        WF_METRIC_PID="$pid" powershell.exe -NoProfile -Command '$p=Get-Process -Id ([int]$env:WF_METRIC_PID) -ErrorAction Stop; [pscustomobject]@{pid=$p.Id;cpu_seconds=$p.CPU;working_set_bytes=$p.WorkingSet64;private_memory_bytes=$p.PrivateMemorySize64;threads=$p.Threads.Count;handles=$p.HandleCount;sampled_at_utc=[DateTime]::UtcNow.ToString("o")} | ConvertTo-Json -Compress' > "$OUTPUT/core-process-$stamp.json" 2>&1 || true
      fi
    fi
    kill -0 "$SERVER_PID" 2>/dev/null || { tail -160 server.log; echo "ERROR: server exited during core observation"; exit 15; }
    sleep 1
  done

  # Let the normal stop lifecycle perform one complete world save. The previous
  # harness forced save-all immediately before stop, causing two consecutive chunk
  # flushes and measuring duplicate I/O rather than graceful shutdown/recovery.
  CURRENT_STAGE="server_core_first_shutdown"
  printf '%s\n' "stop" >> in.txt
  for _ in $(seq 1 120); do kill -0 "$SERVER_PID" 2>/dev/null || break; sleep 1; done
  if kill -0 "$SERVER_PID" 2>/dev/null; then
    echo "ERROR: first server shutdown exceeded 120 seconds"
    exit 16
  fi
  if wait "$SERVER_PID"; then first_exit=0; else first_exit=$?; fi
  SERVER_PID=""
  echo "SERVER_CORE_FIRST_JVM_EXIT_CODE=$first_exit"
  [ "$first_exit" -eq 0 ] || { SERVER_EXIT_CODE="$first_exit"; echo "ERROR: first JVM did not exit cleanly"; exit 17; }
  echo "SERVER_CORE_FIRST_SHUTDOWN=GRACEFUL"

  # Reopen the exact same world directory. Do not delete, regenerate, or copy it.
  CURRENT_STAGE="server_core_restart"
  : > in.txt
  start_line="$(wc -l < server.log)"
  java -Dwf.simulation.tick-multiplier=1 -Xms1G -Xmx3G \
    -XX:+HeapDumpOnOutOfMemoryError \
    -XX:HeapDumpPath="$JAVA_WORK/java_pid%p.hprof" \
    -XX:ErrorFile="$JAVA_WORK/hs_err_pid%p.log" \
    -Xlog:gc*:file="$JAVA_WORK/gc-restart-%p.log":time,uptime,level,tags \
    @user_jvm_args.txt @libraries/net/minecraftforge/forge/$FORGE/win_args.txt nogui \
    < <(tail -n +1 -f in.txt) >> server.log 2>&1 &
  SERVER_PID=$!
  echo "SERVER_CORE_RESTART_PID=$SERVER_PID"
  for i in $(seq 1 180); do
    if tail -n +"$((start_line + 1))" server.log | grep -q 'Done ('; then break; fi
    if ! kill -0 "$SERVER_PID" 2>/dev/null; then
      if wait "$SERVER_PID"; then SERVER_EXIT_CODE=0; else SERVER_EXIT_CODE=$?; fi
      SERVER_PID=""
      tail -160 server.log
      echo "ERROR: JVM exited during same-world restart (code=$SERVER_EXIT_CODE)"
      exit 18
    fi
    sleep 2
  done
  tail -n +"$((start_line + 1))" server.log | grep -q 'Done (' || { echo "ERROR: same-world restart timed out"; exit 19; }
  echo "SERVER_CORE_SAME_WORLD_RESTART=PASS"
  printf '%s\n' "list" "save-all flush" >> in.txt
  sleep 10
  CURRENT_STAGE="server_core_final_shutdown"
  printf '%s\n' "stop" >> in.txt
  for _ in $(seq 1 120); do kill -0 "$SERVER_PID" 2>/dev/null || break; sleep 1; done
  if kill -0 "$SERVER_PID" 2>/dev/null; then
    echo "ERROR: restarted server shutdown exceeded 120 seconds"
    exit 20
  fi
  if wait "$SERVER_PID"; then SERVER_EXIT_CODE=0; else SERVER_EXIT_CODE=$?; fi
  SERVER_PID=""
  echo "SERVER_CORE_FINAL_JVM_EXIT_CODE=$SERVER_EXIT_CODE"
  [ "$SERVER_EXIT_CODE" -eq 0 ] || { echo "ERROR: restarted JVM did not exit cleanly"; exit 21; }
  TERMINATION_EVIDENCE="graceful_stop"
  echo "SERVER_CORE_FINAL_SHUTDOWN=GRACEFUL"
  echo "SERVER_CORE_CONTOUR_RESULT=PASS_PENDING_DIAGNOSTICS"
  CURRENT_STAGE="server_core_complete"
  exit 0
fi

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
  # Leave process ownership and shutdown verification to runtime_exit.
  # Manually waiting here used to clear SERVER_PID before the EXIT trap could
  # record the JVM exit status, making the new PASS contract self-contradictory.
  echo "SAME_SERVER_DUAL_WORLD_GENESIS=PASS_PENDING_GRACEFUL_SHUTDOWN"
  echo "NO_CAMPAIGN_OR_SCENARIO=1"
  CURRENT_STAGE="dual_world_genesis_complete"
  exit 0
fi

echo "ERROR: ordinary-world smoke mode was not selected; refusing scenario execution"
exit 9
