# MAQAN Runtime Stress Lab — 12 Contours

Status: acceptance contract. A contour is NOT PASSED until its real Forge Dedicated Server driver, staged workload, measurements, persistence/recovery, and uploaded evidence all succeed on Windows.

## Execution rules
- Every load driver must enter through existing production commands, domain services, registries, schedulers, entity lifecycle, persistence APIs, and world tick hooks. The driver may pace and observe work; it must not implement a parallel simulation or directly fabricate success markers.
- A driver-only helper is allowed only as an adapter to existing production APIs, with the exact production entry point and side effects documented. Any bypassed subsystem makes the affected stage NOT_IMPLEMENTED, not PASS.
- Validate that workload actually happened from authoritative domain state and durable records, not log markers emitted by the driver itself.
- Build once; each contour gets a separate Forge Dedicated Server process, save, and output directory. Reuse a save only inside that contour's restart/recovery phases.
- Java/Rust unit tests are supplementary and never substitute for server stress.
- Record commit, Java/Forge, runner, JVM args, native backend, seed, mod set, driver version.
- Track canonical records, loaded Minecraft entities, active chunks, and processed records independently.
- Each stage includes cold start, steady state, burst, save, graceful stop, restart, and recovery. Crash fault injection is separate.
- Preserve logs, thread dumps, GC, heap/process samples, save, WAL/checkpoints, and failure classification. Never lower load or hide failures to get PASS.

## Shared workload ladders
- Canonical population/social/economy: 100 → 1k → 10k → 100k → 500k → 1m records where supported.
- Physical agents/entities/pathing/combat: 10 → 100 → 1k → 5k → 10k. Never equate one million canonical records with one million ticking entities.
- Worldgen: 1 → 4 → 16 → 64 → 256 region/chunk batches, then large continuous traversal.
- Soak: 30 min → 2 h → 8 h → overnight after calibration.

## Contours

### 01 — Server Core (foundation)
Forge cold start, idle baseline, command/event bursts, bounded server-thread work, tick loop, save/flush, graceful stop, same-world restart; 30 min / 2 h / 8 h soak. Measure TPS/MSPT p50/p95/p99/max, overruns, queues, CPU, RSS/private bytes, heap, GC pauses, threads/handles, save latency. Fail on crash, unbounded growth, missing save acknowledgement, forced termination or failed restart. Current lifecycle/restart and process/heap/thread sampling is partial; no verified Windows pass. 120 seconds is smoke baseline, not Core stress.

### 02 — Character / Population
Canonical Character creation, UUID uniqueness, registry, lifecycle, needs/intent simulation, bounded processing, WAL append/replay, checkpoint/migration; separate physical WFNpc spawn/despawn. Canonical stages 100 → 1k → 10k → 100k → 500k → 1m; physical bodies independently 10 → 100 → 1k → 5k. Measure throughput, server-thread time/batch, lookup latency, heap/object growth, WAL bytes/force latency, replay throughput, checkpoint time, duplicate/missing IDs and active bodies. Interrupt during creation, partial WAL tail, clean restart, replay/resume without duplicates/skips. Current synthetic synchronous driver was removed because it bypassed the normal tick path and used a broad checkpoint as a proxy for normal per-character WAL writes. No independent Population stress contour is currently implemented or passed. Next implementation must pace creation through existing domain entry points, let the existing server tick/scheduler process it, and verify the actual persistence contract. Do not enable million-scale stages until those paths are proven.

### 03 — Families / Social
Births, aging, death, marriages, kinship, opinions, social memory, events, witness propagation. 1k → 10k → 100k → 500k → 1m canonical people; interaction density 1/10/100 per character per simulated period. Measure event throughput, graph traversal, backlog, per-tick budget, memory/relationship, duplicate delivery, orphan references, graph integrity. Burst births/deaths/marriages; verify idempotency/replay.

### 04 — Settlements / Labor
Workers, professions, job queues, work zones, construction, warehouses, production, route planning/replanning/failures. 1 → 10 → 100 → 1k settlements; 100 → 1k → 10k → 100k canonical workers; 10 → 100 → 1k → 5k physical active workers. Measure jobs/sec, route requests/sec, path expansions, failures, replans, stuck time, chunk tickets, block updates, contention, MSPT. Test blocked paths, inaccessible zones, missing materials, death/reassignment and in-flight task recovery.

### 05 — Economy / Trade / Treasury
Production, consumption, inventory transfers, markets, caravans, routes, taxes, treasury ledgers, WAL/checkpoints. 10 → 100 → 1k → 10k markets/settlements where supported; 1k → 10k → 100k → 1m ledger entries/transactions. Measure transactions/sec, append/force latency, clearing time, backlog, contention, memory, balance invariants. Fault between transaction endpoints; no duplicated/lost goods or money.

### 06 — Politics / States
Realms, titles, rulers, dynasties, vassal graphs, councils, diplomacy, succession, laws, revolts, decisions. 100 → 1k → 10k → 100k → 1m actors/titles where supported; vary graph density/cadence. Measure decision throughput, graph traversal, proposal backlog, succession latency, event fan-out, memory/tick impact. Burst ruler death, succession, revolt, treaty, council changes; verify references and deterministic recovery.

### 07 — Army / RTS
Building recruitment/summons, commanders, armies/corps/units, orders, formations, supply, morale, combat, wounds/losses. 10 → 100 → 1k → 5k → 10k physical units; 100 → 1k → 10k → 100k canonical military Characters. Measure order latency, pathfinding, formation convergence, target acquisition, combat/sec, supply, entity tracking, packet volume, MSPT. Stress mass recruitment, simultaneous orders, commander loss, supply cutoff, retreat and battle save/reload.

### 08 — World Generation
Biomes, terrain, hydrology, chunks, region maps, settlements, borders, deterministic seeds, generation queues. Concurrent chunk/region batches 1 → 4 → 16 → 64 → 256; large exploration, multiple seeds. Measure chunks/sec, latency p50/p95/p99, executor queue, CPU, allocation/GC, disk, cache hit/miss, peak memory, determinism. Test cancellation, restart during generation, reload, seam continuity, no duplicate/omitted work.

### 09 — Dimensions / Planets
Lazy activation, first visit, unload/reload, dimension-local registries, isolation, travel, resource lifecycle. 1 → 2 → 8 → 32 → 128 registered dimensions; 1 → 2 → 4 → 8 active dimensions. Measure startup, loaded levels, chunks/entities per level, memory/dimension, activation/unload latency, cross-dimension references, tick cost. Prove unvisited dimensions do not load/simulate; test repeat visit/unload/restart and identity isolation.

### 10 — Persistence / Recovery
Character/Treasury WAL, checkpoints, snapshots, migrations, cross-partition transfers, world saves. 10k → 100k → 500k → 1m records and increasing transaction rates; vary checkpoint intervals/shards. Measure append/force, bytes/sec, replay records/sec, checkpoint duration, peak memory, disk amplification, recovery time, loss/duplication. Fault inject at transaction boundaries, partial frame, bad checksum, disk full, migration interruption.

### 11 — Long-term Autonomous Life
Player-free population, families, labor, economy, politics, settlements, army, autonomous events. 10k → 100k → 500k → 1m canonical people; 30 min → 2 h → 8 h → overnight. Measure simulation lag, backlog growth, memory/GC slope, WAL growth, event rates, population invariants, birth/death/starvation trends, responsiveness. Fail on unbounded backlog, unexplained memory growth, skipped days, duplicate events, failed save/recovery.

### 12 — Full Integration
All enabled systems simultaneously, shared Character identity, real cross-system interactions. 10% → 25% → 50% → 75% → 100% of individually accepted capacities; 30 min → 2 h → 8 h soak. Concurrent bursts: worldgen + population growth + construction + trade + diplomacy + army combat. Measure total/per-subsystem tick budget, queues, contention, CPU, heap/RSS, GC, disk/WAL, active chunks/entities, domain invariants. PASS only when all invariants and persistence/recovery pass together.

## Result contract
Each contour emits JSON: contour ID, stage, workload totals, elapsed time, throughput, latency percentiles, CPU/memory/GC, threads/queues, persistence/recovery evidence, loaded entities/chunks, warnings, failure class, commit/environment metadata. Failure classes: SERVER, TEST, INFRA, DEGRADED, INCONCLUSIVE, NOT_IMPLEMENTED. Only fully evidenced success is PASS; missing/timed-out stages are never PASS.
