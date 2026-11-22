# MAQAN Runtime Stress Matrix — 12 production contours

This is the acceptance contract for the real Forge Dedicated Server stress lab. A contour is not PASS because it compiles, starts, logs a marker, or completes a smoke test. PASS requires the real production mechanism to survive its declared maximum sustainable workload, soak, save, stop, same-save restart and recovery with intact domain invariants.

## Non-negotiable execution rules
- Each contour runs in a separate Forge Dedicated Server JVM with its own save, seed and evidence folder. Restart/recovery reuses only that contour's save.
- The driver only paces work, invokes production entrypoints and observes. The game scheduler, registries, entities, chunk system, domain services and production persistence perform the actual work.
- No direct private-state writes, fake records, parallel toy simulation, fabricated receipts, or driver-generated PASS markers.
- Build once in dependency order. Run isolated contour servers sequentially on one runner by default to avoid resource contention.
- Canonical Characters and physical Minecraft entities are separate scales. 1m canonical Characters is not 1m ticking entities.
- Each contour: cold start → baseline → staged growth → saturation → maximum sustainable steady state → burst → save/flush → graceful stop → same-save restart → recovery. Add fault injection where applicable.
- Capture MSPT/TPS p50/p95/p99/max, CPU, RSS/native, heap, GC pauses, thread count, queue depth/backlog, disk and WAL latency, chunks, levels, entities, throughput and domain invariants.
- Result classes: PASS, DEGRADED, SERVER_FAILURE, TEST_FAILURE, INFRA_FAILURE, INCONCLUSIVE, NOT_IMPLEMENTED. Never hide a system failure behind a timeout increase or runner classification.

## Load ladders
- Canonical Characters/social/economy: 100 → 1k → 10k → 100k → 500k → 1m.
- Physical NPC/worker/troop entities: 10 → 100 → 1k → 5k → 10k, bounded by actual chunk/entity behavior.
- Settlements: 1 → 10 → 100 → 1k; canonical workers 100 → 100k, physical workers separately.
- Worldgen: 1 → 4 → 16 → 64 → 256 chunk/region batches, then continuous exploration.
- Dimensions: 1 → 2 → 8 → 32 → 128 registered; 1 → 2 → 4 → 8 actually loaded.
- Soak: 30m → 2h → 8h → overnight after preceding stages pass.

## Contour definitions

### 01 — Server Core
Actual Forge Dedicated Server lifecycle, MinecraftServer tick, ServerLevel/ChunkSource, event/command dispatch, entity/chunk activity, save/flush and restart. Stress active chunks/entities, command/event bursts, bounded scheduled work and save pressure. Current driver is a lifecycle acceptance harness, not a maximum-load PASS.

### 02 — Character / Population
Actual CharacterRegistry creation and UUID ownership, PeopleMod normal server tick, PopulationSimulation, durable CharacterWal writes/replay, and physical WFNpc spawning as a separate player-connected workload. Canonical scale to 1m; physical entity scale separately to 10k. Verify all IDs, receipts, needs/duties, WAL durability/replay and no starvation. Driver exists but is not validated. Latest run showed synchronous FileChannel.force on Server Thread through CharacterWal.appendBatch → CharacterWal.append → PeopleMod.persistCharacter → RuntimeStressLab.createOne. Pending change routes only dedicated stress writes through bounded CharacterWalWriter; ordinary transactional writes remain synchronous. Server thread must never wait on writer futures.

### 03 — Families / Social
Real family planning, life-stage transitions, births/deaths, marriage/separation, kinship, inheritance, social contacts, witness/reputation propagation. Scale 1k→1m canonical people; verify no orphaned kin links, duplicate events or unbounded fan-out.

### 04 — Settlements / Labor
Real Settlement/Colony, resident life/task graph, worker roles, job assignment, construction blueprints, resource hauling, storage, routes and path replanning. Scale 1→1k settlements, 100→100k canonical workers and physical workers separately. Faults: blocked paths, missing materials, unloaded chunks, death/role change and interrupted builds.

### 05 — Economy / Trade / Treasury
Real production/consumption, Market, Trade, caravans, TradeNetwork, taxes, treasury and transaction coordinators. Stress 1k→1m transactions and increasing markets/settlements. Faults: duplicate requests, interrupted transfers, partial WAL frame and unavailable destination; verify no lost/duplicated currency or goods.

### 06 — Politics / States
Real Realm/Statecraft, CourtSim/Council, title/dynasty succession, diplomacy, treaties, laws and revolt. Stress actor and realm graph size plus decision bursts. Verify stable identity, succession, proposal delivery and no graph corruption.

### 07 — Army / RTS
Real recruitment through building/catalog entrypoints, ArmyEngine, TroopController/TroopEntity, formations, orders, pathfinding, combat, commander hierarchy, operational supply and morale. Canonical military people to 100k; physical units to 10k. Verify orders, movement, combat outcomes, supply, wounds, retreat and save/reload.

### 08 — World Generation
Actual Forge chunk generator and WorldGenRegion, terrain/biome/hydrology/province/settlement generation, generation executor and world save. Multiple seeds, chunk/region saturation, concurrent exploration, cancellation/restart and deterministic seam checks. Measure chunks/sec, tail latency, queues, memory/GC and disk.

### 09 — Dimensions / Planets
Actual ServerLevel registration, lazy activation, first visit, travel, unload/reload, per-dimension Character partitions and cleanup. Registered dimensions scale to 128; loaded dimensions separately to 8. Verify unvisited dimensions do not load/simulate and revisits preserve state.

### 10 — Persistence / Recovery
Actual CharacterWal, production writers, treasury/settlement ledgers, snapshots, checkpoints, migrations and cross-partition coordinators. Stress 10k→1m records and transaction rates. Inject process interruption, partial tail, checksum corruption, interrupted checkpoint/migration and disk pressure; verify replay and no duplicate/lost transactions.

### 11 — Long-term Autonomous Life
Actual WorldSimMod server tick, native/fallback population compute scheduler, PopulationComputeCoordinator and domain cadences with no player. 10k→1m canonical people, 30m→overnight. Measure simulation lag/backlog slope, memory/GC/WAL growth, life events and recovery.

### 12 — Full Integration
All previously accepted production domains running simultaneously: worldgen, population, social, labor, economy, politics, recruitment, movement and combat. Scale to 100% of each independently accepted capacity. Integration cannot hide an individually failing subsystem.

## Current honest implementation state
- 01 and 02 have runtime drivers, but neither has a completed maximum-load stress PASS.
- 03–12 are mapped to real production systems but do not yet have independent server drivers. CI reports them as NOT_IMPLEMENTED instead of faking test execution.
- The workflow contour matrix is a coverage report, not a substitute for the missing stress drivers.
