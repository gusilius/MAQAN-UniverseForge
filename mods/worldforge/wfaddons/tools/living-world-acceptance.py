#!/usr/bin/env python3
"""Strict evidence gate for MAQAN integrated living-world acceptance.

Exit codes: 0 PASS, 1 FAIL, 2 INCONCLUSIVE/invalid evidence.
"""
import argparse
import json
import math
from pathlib import Path

SUBSYSTEMS = (
    "population", "social", "settlements", "economy", "politics",
    "army_rts", "persistence", "npc_lifecycle", "background_simulation",
    "active_region",
)
CAUSAL_LINKS = (
    "production_to_market", "market_to_treasury_politics",
    "politics_to_recruitment", "recruitment_to_supply",
    "supply_to_combat", "combat_to_households_territory",
    "world_state_to_restart_recovery",
)
METRICS = (
    "tps_min", "mspt_p50", "mspt_p95", "mspt_p99", "mspt_max",
    "cpu_percent_max", "process_working_set_bytes_max", "process_private_bytes_max",
    "heap_used_bytes_max", "heap_committed_bytes_max", "non_heap_used_bytes_max",
    "tick_count", "gc_collection_count", "gc_collection_ms", "gc_pause_ms_max",
    "thread_count_max", "physical_npc_peak", "background_population_peak",
    "queue_depth_max", "scheduler_backlog_max", "oldest_queue_item_age_ms_max",
    "wal_pending_max", "durable_writes", "persistence_lag_ms_max", "snapshot_ms_max", "wal_replayed_records"
)

def fail(message):
    print("LIVING_WORLD_VERDICT=FAIL")
    print("LIVING_WORLD_REASON=" + message)
    raise SystemExit(1)

def inconclusive(message):
    print("LIVING_WORLD_VERDICT=INCONCLUSIVE")
    print("LIVING_WORLD_REASON=" + message)
    raise SystemExit(2)

def require_number(mapping, key):
    value = mapping.get(key)
    if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value):
        inconclusive("missing or invalid numeric metric: " + key)
    return float(value)

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("report", type=Path)
    args = parser.parse_args()
    if not args.report.is_file():
        inconclusive("integrated living-world report does not exist: " + str(args.report))
    try:
        report = json.loads(args.report.read_text(encoding="utf-8"))
    except Exception as exc:
        inconclusive("cannot parse report: " + str(exc))
    if not isinstance(report, dict) or report.get("schema_version") != 1:
        inconclusive("expected integrated report schema_version=1")
    for key in ("commit", "minecraft_version", "forge_version", "java_version",
                "world_id", "seed", "hardware_profile", "stage", "duration_seconds"):
        if report.get(key) in (None, ""):
            inconclusive("missing run identity field: " + key)
    if report.get("duration_seconds", 0) <= 0:
        inconclusive("duration_seconds must be positive")
    stage_order = ["A", "B", "C", "D", "E", "F"]
    if report["stage"] not in stage_order:
        inconclusive("unknown progressive stage: " + str(report["stage"]))
    prior = report.get("previous_stage_verdicts", {})
    if not isinstance(prior, dict):
        inconclusive("previous_stage_verdicts must be an object")
    for required_stage in stage_order[:stage_order.index(report["stage"])]:
        if prior.get(required_stage) != "PASS":
            inconclusive("cannot advance: previous stage " + required_stage + " is not PASS")

    subsystems = report.get("subsystems")
    if not isinstance(subsystems, dict):
        inconclusive("subsystems evidence object missing")
    for name in SUBSYSTEMS:
        entry = subsystems.get(name)
        if not isinstance(entry, dict) or entry.get("status") != "ACTIVE":
            inconclusive("subsystem not proven active: " + name)
        if not entry.get("receipt"):
            inconclusive("subsystem runtime receipt missing: " + name)

    links = report.get("causal_chain")
    if not isinstance(links, dict):
        inconclusive("causal_chain evidence object missing")
    for name in CAUSAL_LINKS:
        entry = links.get(name)
        if not isinstance(entry, dict) or entry.get("observed") is not True:
            inconclusive("causal link not observed: " + name)
        if not entry.get("before") or not entry.get("after") or not entry.get("entity_ids"):
            inconclusive("causal link lacks before/after/entity identity evidence: " + name)

    lifecycle = report.get("npc_lifecycle")
    if not isinstance(lifecycle, dict):
        inconclusive("npc_lifecycle evidence missing")
    for key in ("created", "eligible_for_unload", "unloaded", "reloaded", "same_uuid_verified",
                "inventory_verified", "family_verified"):
        if key not in lifecycle:
            inconclusive("NPC lifecycle field missing: " + key)
    if lifecycle["unloaded"] < 1 or lifecycle["reloaded"] < 1:
        inconclusive("real NPC unload and rematerialization were not both observed")
    if lifecycle["same_uuid_verified"] is not True or lifecycle["inventory_verified"] is not True or lifecycle["family_verified"] is not True:
        fail("NPC identity, inventory or family state changed across LOD unload/reload")

    persistence = report.get("persistence")
    if not isinstance(persistence, dict):
        inconclusive("persistence evidence missing")
    for key in ("wal_durable", "snapshot_durable", "save_completed", "restart_completed",
                "recovery_completed", "state_hash_before", "state_hash_after"):
        if key not in persistence:
            inconclusive("persistence field missing: " + key)
    if any(persistence.get(k) is not True for k in
           ("wal_durable", "snapshot_durable", "save_completed", "restart_completed", "recovery_completed")):
        fail("WAL/snapshot/save/restart/recovery durability check failed")
    if persistence["state_hash_before"] != persistence["state_hash_after"]:
        fail("world state hash differs after restart/recovery")

    metrics = report.get("metrics")
    if not isinstance(metrics, dict):
        inconclusive("runtime metrics object missing")
    values = {key: require_number(metrics, key) for key in METRICS}
    budgets = report.get("budgets")
    if not isinstance(budgets, dict):
        inconclusive("explicit hardware/stage resource budgets missing")
    for key in METRICS:
        if key not in budgets:
            inconclusive("budget not configured for metric: " + key)
        limit = require_number(budgets, key)
        if key == "tps_min":
            if values[key] < limit:
                fail("TPS below configured minimum")
        elif key in ("durable_writes", "tick_count"):
            if values[key] < limit:
                fail("minimum execution/durability target not reached: " + key)
        elif values[key] > limit:
            fail("resource/performance budget exceeded: " + key)

    client = report.get("client_metrics")
    if not isinstance(client, dict) or client.get("source") != "real_minecraft_client":
        inconclusive("FPS/frame-time evidence must come from a real Minecraft client")
    for key in ("fps_min", "frame_ms_p95", "frame_ms_p99", "frame_ms_max"):
        if key not in client:
            inconclusive("client metric missing: " + key)
        require_number(client, key)
    client_budgets = report.get("client_budgets")
    if not isinstance(client_budgets, dict):
        inconclusive("explicit real-client performance budgets missing")
    for key in ("fps_min", "frame_ms_p95", "frame_ms_p99", "frame_ms_max"):
        if key not in client_budgets:
            inconclusive("client budget missing: " + key)
        limit = require_number(client_budgets, key)
        value = float(client[key])
        if key == "fps_min":
            if value < limit:
                fail("real-client FPS below configured minimum")
        elif value > limit:
            fail("real-client frame-time budget exceeded: " + key)
    print("LIVING_WORLD_VERDICT=PASS")
    print("LIVING_WORLD_STAGE=" + str(report["stage"]))
    print("LIVING_WORLD_COMMIT=" + str(report["commit"]))
    print("LIVING_WORLD_METRICS=" + json.dumps(values, sort_keys=True))
    return 0

if __name__ == "__main__":
    main()
