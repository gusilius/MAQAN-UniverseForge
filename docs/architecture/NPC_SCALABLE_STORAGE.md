# Scalable NPC Persistence and Cross-Mod Contract

## Goal

Remove population-size-dependent full-registry persistence from the server tick. Preserve one canonical Character identity and all existing relationships, properties, roles, estate/inventory transaction markers, and mod-facing behavior.

This document is the integration contract and migration plan. It is not a claim that the storage implementation or native bridge is already complete.

## Architectural boundaries

- **Java Character Authority remains canonical.** UUID/global ID, ownership partition, lifecycle, inventory, estate, and game-world integration stay in the existing authority. No parallel Rust NPC identity.
- **Rust is a compute engine, not a persistence authority.** Population indexing, shard routing calculations, batch planning, optional relationship/population calculations may be moved into native code once a versioned bridge is in place. Rust must not directly access Minecraft objects.
- **Storage is an adapter behind the existing PeopleMod boundary.** Mods continue using the existing Character APIs and authority. They must not open shard files, WALs, or rely on physical shard layout.
- **Server tick is bounded.** Tick code may capture a bounded immutable change batch and enqueue it. File IO, compression, checkpoints, replay, and large serialization must run off the tick thread. Tick paths must never wait on storage worker futures.
- **Backpressure is explicit.** Queue capacity and dirty-record memory are bounded; overflow is observable and safely handled without falsely advancing durable cursors or silently dropping committed mutations.
- **Durability acknowledgement is explicit.** Transaction-sensitive operations (inventory sharing, estate transfer, ownership transfer) must not report durable completion until their WAL commit and required destination-first ordering is confirmed. Ordinary simulation mutations may be coalesced according to documented durability policy.
- **WAL and checkpoints.** WAL entries are versioned, checksummed, sequence-numbered, idempotently replayable, and append-only until checkpoint/rotation. Checkpoints use temporary files, flush/sync as appropriate, atomic replacement, and verified manifests. Recovery detects incomplete tails and preserves the last valid checkpoint.
- **Shards.** Shards partition records by stable Character global ID (not by transient resident/chunk state). Routing is deterministic, versioned, and independent of map residency. Relationship indexes are separately rebuildable or maintained through journaled mutations.
- **Compatibility.** Existing wf-people/1, /2, /3 snapshots remain readable. Migration is resumable, verifies identity and record counts/checksums, retains the old snapshot until validation passes, and does not regenerate IDs or drop props/family/inventory/estate markers.
- **Lifecycle and mod notifications.** Existing CharacterRegistry lifecycle events/revisions remain the contract. The storage layer observes canonical mutation batches; it does not replace or duplicate mod listeners. Consumers should not be coupled to shard placement or persistence timing.

## Mod integration requirements

| Consumer | Contract |
|---|---|
| Worldgen / Genesis | Submit population batches; materialization cursor advances only after durable population identity checkpoint rules are satisfied. No per-NPC full-registry write. |
| World simulation | Use batch mutations and bounded tick budgets; off-thread calculations operate on immutable snapshots and return validated intents. |
| Settlements / workers | Keep worker identity, family, skills, profession, inventory, and ownership in canonical Characters; no separate worker save authority. |
| Army / RTS / troops | Role conversion is a canonical Character mutation; UUID, traits, equipment, family, and history remain unchanged. Troop/army data stores references to Characters, not duplicate NPC records. |
| Politics / titles / diplomacy | Posts, title holder, political relationships, and inheritance refer to global IDs; persistence partitioning is invisible to these modules. |
| Economy / inventory / estates | WAL-backed transaction ordering; destination-first transfer markers and estate recovery remain enforced. These operations cannot use lossy/coalesced durability. |
| UI / dialogue / quests | Read through existing APIs or snapshots; UI requests cannot force full-registry serialization. |

## Required implementation phases

1. **Baseline and compatibility inventory:** locate all full-registry writes, direct Character mutation paths, lifecycle listener coverage, transaction markers, and consumers. Add measurable telemetry before behavior changes.
2. **Record serialization and store API:** versioned Character record codec and stable shard key; immutable snapshots on server thread; validate round-trip for all Character fields.
3. **WAL implementation:** append/commit/replay, checksums, bounded buffers, crash-tail handling, idempotency and transaction IDs. Implement storage in Java first unless the current native build pipeline can support a portable bridge without delaying correctness.
4. **Async bounded persistence:** writer worker, bounded queue, coalescing ordinary mutations, transaction barriers for critical mutations, bounded shutdown flush, no tick-thread blocking.
5. **Snapshot/shards and migration:** checkpoints, rotation, resumable migration from legacy registry files, verification, rollback path, legacy retention.
6. **Mod adapters:** integrate all listed consumers and ensure storage is invisible through public Character APIs.
7. **Native Rust bridge (optional after storage contract stabilizes):** versioned ABI, DTO-only inputs/outputs, no pointers to game objects, deterministic tests and fallback behavior. Rust optimization must be benchmarked and justified; do not move file durability semantics into an unverified native bridge.
8. **Verification:** unit/property tests; migration fixtures for legacy formats; cross-partition relationships; estate/inventory transaction recovery; queue overload; shutdown; corruption/torn writes; stress fixtures at 1k, 10k, 50k, 100k, 250k Characters; measure tick latency, IO throughput, RAM, load/recovery, and consistency. Run full Windows CI and runtime acceptance.

## Acceptance criteria

- No full-registry write in NPC materialization or routine tick mutation paths.
- Tick code never blocks waiting for persistence.
- A mutation cannot be acknowledged durable before its commit record is durable.
- No identity, family links, posts, traits, props, inventory, estate, or transaction markers are lost in migration or recovery.
- Migration is resumable and old saves remain recoverable until validation.
- All mod consumers use canonical Character identity and remain independent of shard layout.
- CI passes, and runtime acceptance confirms Genesis/materialization completes without watchdog stalls.
