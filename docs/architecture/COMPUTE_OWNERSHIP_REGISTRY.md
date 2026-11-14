# Compute Ownership Registry — UniverseForge

**Status:** source-audited registry; first asynchronous WorldSim execution slice implemented, CI/campaign verification pending.  
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
| `worldsim.emergence.advance` | Emergence/population macro-state | Rust JNI on the shared WorldSim compute worker | Rust | Build snapshot, persist state, materialize canonical Characters/settlements/polities, validate/apply events | `WorldSimMod.processServerTick` → `WorldSimComputeScheduler`; `NativeWorldSimulation.advanceEmergence`; Rust `emergence::advance`; JNI `nativeAdvanceEmergence` | Async single-flight execution implemented; CI/campaign and resumable catch-up verification pending |
| `worldsim.decide` | Strategic proposals | Rust JNI on the same shared WorldSim compute worker | Rust | Build canonical snapshot; validate and execute proposals through ActionExecutionService; persist receipts/events | `WorldSimMod.processServerTick` → `WorldSimComputeScheduler`; `NativeWorldSimulation.decide`; Rust `decide`; JNI `nativeDecide` | Async single-flight execution implemented; CI/parity verification pending |
| `worldsim.generate` | Initial world generation | Rust JNI function exists; Java wrapper exists, but no production caller was found in the current source search | Rust | Java owns world validation/materialization and applying generated data | `NativeWorldSimulation.generate`; JNI `nativeGenerate`; Rust `worldgen::generate_world` | Dormant/unverified entry point; do not create a second generator or move it into the scheduler until its actual caller and ownership are identified |
| `worldsim.snapshot` | Domain/Minecraft observation | Java | Java | Sole adapter from canonical domain APIs and loaded Minecraft state | `WorldSimMod.snapshot` | Remains Java; must run on server thread |
| `worldsim.materialize.people` | Canonical Character/Origins materialization | Java | Java | Create/update existing canonical Character and Origins records, preserve UUID and relationships | `WorldSimMod.syncEmergentPeople`; `EmergentPopulationIndex`; canonical `CharacterRegistry` | Java execution boundary; incremental bounded batches not implemented |
| `worldsim.materialize.polities` | Realm/settlement projection | Java | Java | Reconcile existing realm/settlement authorities and physical world | `WorldSimMod.syncEmergentPolities`; Realm/Settlement APIs | Java execution boundary; requires audit of all projection costs |
| `worldsim.proposal.apply` | Authoritative action execution | Java | Java | Validate and commit through ActionExecutionService and domain owner | `WorldSimMod.processServerTick`; persisted action receipts | Remains Java; never move Minecraft side effects to Rust |

## Single-flight / async migration design (first implementation in source; verification pending)

The first migration slice is `worldsim.emergence.advance` + `worldsim.decide`. They must share one bounded WorldSim compute scheduler unless profiling proves independent scheduling is necessary. Do not create one executor per domain.

1. **Implemented:** server thread builds a detached immutable snapshot/request and copies byte arrays. No Minecraft object, Level, Entity, registry or mutable Gson object crosses to a worker.
2. **Partially implemented:** ticket records world save identity, operation day/revision and server lifecycle epoch. A shared all-domain mutation revision and schema/engine version fence remain open; ticket now carries the combined operation key `worldsim.compute.cycle`.
3. **Implemented:** one worker invokes both native methods sequentially and returns immutable result bytes/frame. It performs no Forge calls and applies no effects. A process-wide native lease prevents overlap across scheduler instances during server restart.
4. **Implemented with remaining limits:** server thread polls completed frames and checks lifecycle epoch, world save identity, operation key and submitted day token before apply; result schema and idempotent domain receipts are validated by existing apply pipeline. Cross-domain mutation revision is not yet available.
5. Lifecycle/world/token mismatches are rejected and diagnosed. A shared revision that detects every external domain mutation is still required for a complete freshness fence.
6. **Implemented single-flight backpressure:** at most one request is active; no unbounded queue. While busy, the next snapshot is not enqueued; after completion the coordinator builds a fresh snapshot, while Rust emergence resumes from the last committed state and current simulation day. Campaign catch-up parity must verify that no elapsed work is lost.
7. Stop/reload increments the lifecycle epoch, rejects in-flight completions, and persists the last committed state/cursor. Native work must not outlive the world authority it belongs to.
8. **Production owner:** Rust async is the only WorldSim compute path; no parallel Java compute fallback or shadow calculation runs. If a safe fallback is later needed, it must be exclusive per operation/revision.
9. Existing profiler measures snapshot, native stages, validation/apply and persistence. Queue wait and end-to-end compute latency are now recorded by the scheduler result and existing profiler; runtime distribution data awaits CI/campaign artifacts.

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


## Current implementation evidence (2026-11-14)

- `WorldSimComputeScheduler.java`: bounded single-flight worker, immutable byte copies, world/revision/lifecycle ticket, process-wide native semaphore, server-thread polling boundary.
- `WorldSimMod.java`: `advanceEmergence` and `decide` execute sequentially on the worker; all state sync, proposal validation, event publication and persistence remain on the server thread.
- `WorldSimComputeSchedulerTest.java`: backpressure, defensive-copy, ticket-fence and lifecycle-overlap tests. Windows CI and autonomous campaign evidence are pending.
- Remaining limitation: no global mutation revision exists across all authoritative addon domains yet. Do not claim full snapshot freshness until this contract is implemented and tested.
