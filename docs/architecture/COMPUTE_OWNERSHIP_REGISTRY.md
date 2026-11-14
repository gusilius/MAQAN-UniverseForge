# Compute Ownership Registry — UniverseForge

**Status:** initial source-audited registry; not a claim that Rust migration is complete.  
**Repository:** `gusilius/MAQAN-UniverseForge`  
**Branch:** `feature/autonomous-world-simulation-engine`  
**Last audited:** 2026-11-14  
**Architecture contract:** `docs/architecture/RUST_COMPUTE_MIGRATION_AND_OPTIMIZATION_CONTRACT.md`

This registry records only ownership supported by the current source audit. Unknown owners and unverified integrations are explicitly marked; they must not be guessed.

## Ownership rules

- One production executor per operation key and input revision.
- Rust computes only over immutable input; Java owns Minecraft/domain validation and side effects.
- Shadow parity is test-only, isolated, and never applies side effects.
- A fallback and Rust execution are mutually exclusive for one operation/revision.
- Every asynchronous result must carry world identity, operation key, input revision, schema/engine version and generation epoch; stale results are rejected before apply.
- This registry is updated in the same change that moves an operation's production owner.

## Audited operations

| Operation key | Domain | Current production compute owner | Target owner | Java responsibility | Source evidence | Migration state |
|---|---|---|---|---|---|---|
| `worldsim.emergence.advance` | Emergence/population macro-state | Rust JNI, invoked synchronously by Java server tick | Rust | Build snapshot, persist state, materialize canonical Characters/settlements/polities, validate/apply events | `wf-worldsim/.../mc/WorldSimMod.processServerTick`; `NativeWorldSimulation.advanceEmergence`; Rust `emergence::advance`; JNI `nativeAdvanceEmergence` | Rust algorithm exists; async execution and bounded resumable catch-up not implemented |
| `worldsim.decide` | Strategic proposals | Rust JNI, invoked synchronously by Java server tick | Rust | Build canonical snapshot; validate and execute proposals through ActionExecutionService; persist receipts/events | `WorldSimMod.processServerTick`; `NativeWorldSimulation.decide`; Rust `decide`; JNI `nativeDecide` | Rust algorithm exists; async execution not implemented |
| `worldsim.snapshot` | Domain/Minecraft observation | Java | Java | Sole adapter from canonical domain APIs and loaded Minecraft state | `WorldSimMod.snapshot` | Remains Java; must run on server thread |
| `worldsim.materialize.people` | Canonical Character/Origins materialization | Java | Java | Create/update existing canonical Character and Origins records, preserve UUID and relationships | `WorldSimMod.syncEmergentPeople`; `EmergentPopulationIndex`; canonical `CharacterRegistry` | Java execution boundary; incremental bounded batches not implemented |
| `worldsim.materialize.polities` | Realm/settlement projection | Java | Java | Reconcile existing realm/settlement authorities and physical world | `WorldSimMod.syncEmergentPolities`; Realm/Settlement APIs | Java execution boundary; requires audit of all projection costs |
| `worldsim.proposal.apply` | Authoritative action execution | Java | Java | Validate and commit through ActionExecutionService and domain owner | `WorldSimMod.processServerTick`; persisted action receipts | Remains Java; never move Minecraft side effects to Rust |

## Single-flight / async migration design (not yet implemented)

The first migration slice is `worldsim.emergence.advance` + `worldsim.decide`. They must share one bounded WorldSim compute scheduler unless profiling proves independent scheduling is necessary. Do not create one executor per domain.

1. Server thread builds a detached immutable snapshot/request. No Minecraft object, Level, Entity, registry or mutable Gson object crosses to a worker.
2. A single-flight operation token records world ID, operation key, input revision/day, schema version and server lifecycle epoch.
3. One worker invokes the native methods and returns immutable result bytes/frame. It performs no Forge calls and applies no effects.
4. Server thread polls completed frames; it checks epoch, world identity, expected input revision, result schema and idempotency before any state mutation.
5. If a newer input revision exists, stale results are rejected and diagnosed; they are never applied to current state.
6. Backpressure is explicit. Do not queue unbounded snapshots. If busy, retain the next required work as a durable/coalesced pending operation; never silently drop elapsed simulation work.
7. Stop/reload increments the lifecycle epoch, rejects in-flight completions, and persists the last committed state/cursor. Native work must not outlive the world authority it belongs to.
8. Keep the current synchronous implementation as an exclusive fallback until async parity and regression gates pass. Never run synchronous and asynchronous implementations for the same operation/revision.
9. Measure snapshot, encode/decode, queue wait, native compute, result validation, apply and persistence separately.

## External optimization inventory

**Status: incomplete.** The repository's addon dependencies are visible in `wf-worldsim/build.gradle`, but the complete runtime modpack and exact loader/version combination have not been inventoried. No third-party optimization owner is asserted without evidence.

| Area | Current owner | Required audit before adding optimization | Status |
|---|---|---|---|
| Forge/Minecraft tick lifecycle | Minecraft/Forge plus registered addon listeners | Enumerate all ServerTickEvent listeners, event subscribers, mixins/coremods and their execution cost | Open |
| WorldSim native compute | UniverseForge Rust through JNI | Replace synchronous invocation with one bounded scheduler; do not add another per-addon pool | Open |
| Character identity/index | Canonical wf-people CharacterRegistry; EmergentPopulationIndex is a short-lived lookup index | Confirm invalidation and no second identity store | Existing source; regression required |
| Treasury journal/indexes | Canonical TreasuryLedger | Audit every `journal()` call site and index invalidation; no second ledger/cache | WF-RT-0013 partially addressed |
| Chunk loading/generation | Minecraft/Forge and installed integrations | Inventory actual runtime mods and hooks before adding parallel chunk work | Open |
| NPC pathfinding | Existing settlement/NPC implementations and Minecraft navigation | Enumerate all planners, path caches, navigation tick handlers and compatibility hooks | Open |
| Client rendering | Minecraft renderer and installed renderer mods | Detect actual renderer/version; do not inject into unknown render pipeline | Open |
| Thread pools/executors | Not yet fully inventoried | Search all addon sources for Executor, ForkJoinPool, ThreadPoolExecutor, CompletableFuture and custom schedulers | Open |

## Required source audit before each new domain

Search the entire repository (including generated source, addon modules, mixins and build-time patches) for:
- domain calculation methods and callers;
- Forge tick/event listeners and scheduled callbacks;
- Rust JNI entry points and any other native libraries;
- caches/indexes and their invalidation owners;
- executor pools, queues, futures and shutdown hooks;
- external mod hooks and runtime compatibility declarations.

Record the exact class/method and tests before assigning or changing ownership. A target owner in the architecture contract is not evidence that migration has happened.

## Acceptance gates

- Rust/Java parity fixtures with deterministic seeds and stable ordering.
- No duplicate production calculation or duplicate event/action receipts.
- Stale-result, lifecycle-epoch, backpressure, shutdown and restart tests.
- Save/reload recovery of pending cursor and committed state.
- Windows x64 native load + Forge build + addon logic tests.
- Full campaign and ordinary-world regression. CI build success alone is not campaign acceptance.
