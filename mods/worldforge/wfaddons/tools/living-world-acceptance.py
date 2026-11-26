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
    "cpu_percent_max", "process_working_set_bytes_max",
    "heap_used_bytes_max", "gc_pause_ms_max", "queue_depth_max",
    "scheduler_backlog_max", "persistence_lag_ms_max",
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
        elif values[key] > limit:
            fail("resource/performance budget exceeded: " + key)

    client = report.get("client_metrics")
    if not isinstance(client, dict) or client.get("source") != "real_minecraft_client":
        inconclusive("FPS/frame-time evidence must come from a real Minecraft client")
    for key in ("fps_min", "frame_ms_p95", "frame_ms_p99", "frame_ms_max"):
        if key not in client:
            inconclusive("client metric missing: " + key)
    print("LIVING_WORLD_VERDICT=PASS")
    print("LIVING_WORLD_STAGE=" + str(report["stage"]))
    print("LIVING_WORLD_COMMIT=" + str(report["commit"]))
    print("LIVING_WORLD_METRICS=" + json.dumps(values, sort_keys=True))
    return 0

if __name__ == "__main__":
    main()
