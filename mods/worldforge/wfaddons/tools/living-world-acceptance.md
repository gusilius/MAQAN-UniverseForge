# MAQAN integrated living-world acceptance

## Purpose

The acceptance target is one continuously running Minecraft world, not a sum of unrelated subsystem stress tests. Population, social life, settlements, economy, politics, military/RTS, persistence, and runtime lifecycle must advance together and exchange canonical state.

An isolated stress test is diagnostic evidence only. It can locate a bottleneck; it cannot establish whole-world readiness.

## Existing work is part of the acceptance contract

The integrated scenario must exercise the production mechanisms already developed:

- canonical Character identity and role changes without UUID duplication;
- bounded tick work, queues, batch processing, and back-pressure;
- CharacterAccessService leases and safe lease release;
- physical NPC LOD/materialization and unload/reload lifecycle;
- WAL durability, incremental snapshots, save/load, restart recovery;
- bounded caches, cleanup/eviction, and memory reclamation;
- background simulation for distant populations while the active region uses real Minecraft entities, navigation, work, construction, and combat.

A mechanism is not considered verified merely because a class, flag, or stress driver exists. The report must show runtime counters and before/after state proving it executed.

## Progressive stages

A stage advances only after the previous stage passes. Never jump to the million-character experiment to hide a failure at a smaller scale.

1. **Stage A — local living settlement.** Small population; physical NPCs, family/social state, work orders, production, stock changes, market exchange, treasury/tax, recruitment, supply consumption, save/load and restart.
2. **Stage B — growing world.** Multiple settlements and organizations. The same characters move between civilian, worker, and military roles. Trade routes, migration, diplomacy, taxes, recruitment, supply and household changes interact.
3. **Stage C — regional world.** Several active and distant settlements. Nearby NPCs are physical entities; distant populations use background simulation. LOD transitions must unload and rematerialize eligible NPCs without identity or inventory loss.
4. **Stage D — large world.** Increase population and settlement count only within measured resource budgets. Exercise wars, casualties, injuries, inheritance, territory, market shocks, and recovery while all domains remain active.
5. **Stage E — massive world.** Increase scale progressively after D passes. Verify bounded queues, scheduler backlog, persistence lag, heap/GC and memory reclamation over a sustained run.
6. **Stage F — extreme experiment.** Million-character or higher experiments are opt-in research runs, not ordinary PR gates. They do not compensate for failures in A–E.

## Required causal chain

One report must prove the same world and canonical identities traverse the following chain:

1. settlement workers produce real resources;
2. resource stocks and market prices/transactions change;
3. treasury/tax/political state reacts to economic activity;
4. an organization recruits canonical Characters into an army;
5. army supply consumes resources from the same economy;
6. movement/combat causes losses, injuries, occupation or territory change;
7. households, migration, inheritance, reputation and political control react;
8. durable save, shutdown, restart and recovery preserve the resulting state.

Each link needs an observed before/after receipt and stable entity IDs. Merely reporting that a subsystem is "enabled" is insufficient.

## Minecraft runtime evidence required at every stage

Every stage report must identify the exact commit, Minecraft/Forge/JVM versions, seed, world, settings, hardware/runner and scenario duration. Capture:

- server: TPS plus MSPT p50/p95/p99/max, tick count, watchdogs and server-thread stalls;
- process: CPU, working set/private bytes, JVM heap used/committed, non-heap if available, thread and handle counts;
- GC: collection count/time, pause count and pause p50/p95/max; GC log attached;
- work scheduling: queue depth/high-water, accepted/completed/rejected work, scheduler backlog and oldest-item age;
- persistence: WAL queue/depth, pending and durable writes, snapshot duration, persistence lag, replayed records, recovery result;
- NPC lifecycle: canonical population, physical entities by dimension, eligible/unloaded/reloaded counts, LOD transitions, UUID/inventory/family checks;
- world simulation: active and background population counts, settlements, workers, orders, production, stocks, trades, taxes, armies, supply and combat receipts;
- client: FPS and frame-time p50/p95/p99/max from a real Minecraft client session. A dedicated-server run cannot report FPS; mark it NOT_MEASURED, never infer it.

Take an idle reference and workload samples in the same run, but label this as an in-process reference, not a vanilla A/B comparison. For MAQAN overhead attribution, also run matched vanilla and modded baselines on the same hardware, seed, view/simulation distances and duration. Report the delta. Feature-level attribution requires profiler spans or controlled feature toggles.

## Resource budgets and stop conditions

Budgets are explicit per hardware profile and stage. Record the configured TPS/MSPT, RAM/heap, CPU, GC, queue, persistence-lag and duration limits in the report. Abort safely before exhausting the runner or corrupting a world. Do not invent a universal TPS or RAM threshold and do not silently relax a budget after a failure.

## Verdicts

- **PASS:** all required subsystems and causal links have runtime evidence; persistence/recovery and NPC unload/reload identity checks pass; every required metric is present; configured budgets are respected; shutdown is graceful.
- **FAIL:** an observed invariant violation, crash, OOM, watchdog, unbounded backlog, durability/identity loss, or exceeded configured budget.
- **INCONCLUSIVE:** evidence is missing, a subsystem did not execute, a client-only metric was not collected, the run ended early, or a required driver is not implemented.

Missing evidence is never PASS. A successful build, pure-Java harness, file-presence check, isolated contour, or server startup alone cannot pass integrated world acceptance.

## CI policy

Keep fast compile/domain checks on ordinary changes. Run the integrated small-world Minecraft scenario as the runtime acceptance gate. Larger stages are progressive, manually or deliberately scheduled on the existing workflow; do not launch a separate multi-hour million-NPC test on every push. Keep subsystem-only stress drivers as targeted diagnostics. Do not create another workflow for this redesign.

## Current capability inventory (baseline, not a pass)

At the time this contract was introduced, the repository had separate runtime drivers for population, social life and settlements; economy had a separate Java stress group. The workflow's 12-entry "driver inventory" only checked path presence for many entries. The integrated orchestration and complete causal-chain evidence were not yet established. This inventory must be updated from observed runtime results, not assumptions.
