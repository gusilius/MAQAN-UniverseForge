# Compute Ownership Registry — UniverseForge

**Status:** source-audited registry; first asynchronous WorldSim execution slice implemented, CI/campaign verification pending.  
**Repository:** `gusilius/MAQAN-UniverseForge`  
**Branch:** `feature/autonomous-world-simulation-engine`  
**Last audited:** 2026-11-16  
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
| `army.strategic.supply.batch` | Strategic army supply, starvation and supply morale | Rust JNI, one synchronous bounded batch during `ArmyEngine.supplies` | Rust | Java snapshots canonical Army counts and resolves home/ally/occupation ownership; Java applies supply/morale/casualties, starvation events and persistence | `ArmyEngine.supplies` → `ArmyStrategicCompute.tryComputeSupply` → `NativeWorldSimulation.advanceArmyStrategic`; Rust `strategic_army::compute` | Source implementation; initial native CI exposed a missing test-only Deserialize derive on the response frame, corrected in follow-up. Native Windows/Linux and Java dependency-closure validation pending. Provider absence selects exclusive legacy Java calculation; failure after provider ownership fails closed. No new worker or scheduler |\n| `army.combat.batch` | Strategic battles across independent baronies | Rust JNI, one bounded batch per strategic day | Rust | Capture immutable rosters, unit definitions, seed and defender flag; Java applies casualties, morale, war score, events, retreats and persistence | `ArmyEngine.battles` → `ArmyCombatCompute.trySimulateBatch` → `NativeWorldSimulation.advanceArmyCombatBatch`; Rust `army::simulate_batch` → `army::simulate` | Source implementation; Windows/Linux native tests/builds and Java/Forge dependency-closure CI pending. Provider absence selects exclusive Java fallback; provider failure after ownership fails closed. No additional worker or scheduler |
| `troops.squad_morale.batch` | Tactical squad morale | Rust JNI, one synchronous batch call from `TroopController.step` every 10 server ticks | Rust | Snapshot scalar morale inputs and collect squads on server thread; apply morale, routing, aura damage, healing and canonical Character sync on server thread | `TroopsMod.tick` → `TroopController.step` → `Morale.stepBatch` → `TroopCompute.moraleBatch`; Rust `troops::morale_batch` / `troops::morale` | Batch transport implemented in source; Rust Windows/Linux tests and native builds passed, Java regression and 5-addon dependency-closure build passed. Source parity audit found and corrected a routed-destination regression in follow-up; gameplay/campaign verification remains pending. No new executor; Java fallback is exclusive when native provider is unavailable |

## Single-flight / async migration design (first implementation in source; verification pending)

The first migration slice is `worldsim.emergence.advance` + `worldsim.decide`. They must share one bounded WorldSim compute scheduler unless profiling proves independent scheduling is necessary. Do not create one executor per domain.

1. **Implemented:** server thread builds a detached immutable snapshot/request and copies byte arrays. Snapshot carries `schema_version=1`. No Minecraft object, Level, Entity, registry or mutable Gson object crosses to a worker.
2. **Partially implemented:** ticket records world save identity, operation day/revision, lifecycle epoch, snapshot schema version, native ABI version and combined operation key `worldsim.compute.cycle`; JNI validates schema and the loaded library reports ABI version. A shared all-domain mutation revision remains open.
3. **Implemented:** one worker invokes both native methods sequentially and returns immutable result bytes/frame. It performs no Forge calls and applies no effects. A process-wide native lease prevents overlap across scheduler instances during server restart.
4. **Implemented with remaining limits:** server thread polls completed frames and checks lifecycle epoch, world save identity, operation key and submitted day token before apply; result schema and idempotent domain receipts are validated by existing apply pipeline. Cross-domain mutation revision is not yet available.
5. Lifecycle/world/token mismatches are rejected and diagnosed. A shared revision that detects every external domain mutation is still required for a complete freshness fence.
6. **Implemented single-flight backpressure:** at most one request is active; no unbounded queue. While busy, the next snapshot is not enqueued; after completion the coordinator builds a fresh snapshot, while Rust emergence resumes from the last committed state and current simulation day. Campaign catch-up parity must verify that no elapsed work is lost.
7. Stop/reload increments the lifecycle epoch and rejects in-flight completions. Native emergence state is staged and committed only after apply stages complete; a durable cross-domain frame receipt/resumable apply journal is still required for full crash consistency. Native work must not outlive the world authority it belongs to.
8. **Production owner:** Rust async is the only WorldSim compute path; no parallel Java compute fallback or shadow calculation runs. If a safe fallback is later needed, it must be exclusive per operation/revision.
9. Existing profiler measures snapshot, native stages, validation/apply and persistence. Queue wait and end-to-end compute latency are now recorded by the scheduler result and existing profiler; runtime distribution data awaits CI/campaign artifacts.

## External optimization inventory

**Status: incomplete.** The repository's addon dependencies are visible in `wf-worldsim/build.gradle`, but the complete runtime modpack and exact loader/version combination have not been inventoried. No third-party optimization owner is asserted without evidence.

| Area | Current owner | Required audit before adding optimization | Status |
|---|---|---|---|
| Forge/Minecraft tick lifecycle | Minecraft/Forge plus registered addon listeners | Enumerate all ServerTickEvent listeners, event subscribers, mixins/coremods and their execution cost | Open |
| WorldSim native compute | UniverseForge Rust through JNI | WorldSim emergence/decide use the shared bounded scheduler; troop morale uses one synchronous batched JNI call per controller pass and does not create another pool | Mixed: WorldSim async path; troop morale batch path now source-implemented |
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


## Current implementation evidence (2026-11-16)\n\n- `ArmyStrategicCompute.java` and Rust `strategic_army.rs`: strategic supply batch is computed by Rust over detached Army scalar snapshots. Java retains territorial ownership resolution, canonical Army mutations, casualties, events and persistence. The Java legacy loop is exclusive fallback only when native provider is unavailable or the snapshot is outside the supported envelope.\n\n## Previous implementation evidence (2026-11-14)

- `WorldSimComputeScheduler.java`: bounded single-flight worker, immutable byte copies, world/revision/lifecycle ticket, process-wide native semaphore, server-thread polling boundary.
- `WorldSimMod.java`: `advanceEmergence` and `decide` execute sequentially on the worker; all state sync, proposal validation, event publication and persistence remain on the server thread.
- `WorldSimComputeSchedulerTest.java`: backpressure, defensive-copy, ticket-fence and lifecycle-overlap tests. Windows CI and autonomous campaign evidence are pending.
- Remaining limitation: no global mutation revision exists across all authoritative addon domains yet. Do not claim full snapshot freshness until this contract is implemented and tested.


## Apply atomicity status

The coordinator now stages the candidate Rust emergence state and advances the committed in-memory cursor only after materialization, event publication and proposal processing complete. This prevents the Rust cursor itself from moving ahead on an apply exception. It does **not** make independent Character, Settlement, EventLog and other domain persistence operations one atomic transaction. Track that remaining work under `WF-RT-0021`; do not add a parallel journal or event store—extend existing canonical receipts/IDs.


## Presentation and history projections

| Operation key | Domain | Current owner | Optimization boundary | Evidence / status |
|---|---|---|---|---|
| `worldsim.chronicle.ordering` | Chronological presentation of canonical EventLog facts | Java `HistoricalChronicle` | O(1) append for ordered events; binary-search insertion for delayed events; logical head + batched prefix compaction at 4,096 evictions; no second event store and no simulation authority | WF-RT-0022; 2,000-event ordering and 100,050-event bounded-capacity tests added; Windows/campaign profile pending |


## Next cross-domain freshness blocker

WF-RT-0023 tracks the missing shared mutation revision across authoritative domain write APIs. The current WorldSim ticket's day/revision token is not a substitute for a global domain revision vector. Extend canonical write boundaries; do not create a duplicate WorldSim-only shadow state/counter.


## Mutation revision audit — WF-RT-0023

- `CharacterRegistry.revision()` is explicitly a membership/identity revision, not a state revision. Current increments are on character creation and registry restore; lifecycle changes to posts, profession, employment, duties, family, death and entity bindings are not comprehensively represented.
- `Settlement` has resource/placement receipts and uses the canonical TreasuryLedger, but no single revision covering all settlement/resource/worker/building/task mutations was found.
- `WorldForgeApi.fireChange` observes map-action notifications; it is not a universal hook for addon tick handlers or direct domain API writes.
- Therefore none of these existing counters/notifications may be treated as a complete snapshot freshness token. A WorldForge Core revision coordinator must be wired into verified canonical write boundaries, domain by domain, before WorldSim uses it as an apply fence.


## Shared revision coordinator foundation — WF-RT-0023

| Operation key | Canonical owner | Revision lane | Wiring state |
|---|---|---|---|
| `worldforge.events` | `WorldForgeApi.events() / EventLog` | `EVENTS_ACTIONS` | `post`, `clear`, `fromJson` commit after successful canonical journal mutation; callbacks run after commit |
| Character / Origins / Realm / Settlement / Economy / Army / Inventory | Existing owning addon APIs | Dedicated coordinator lanes are defined | Not yet wired; do not treat vector as complete |

`DomainMutationRevisions` lives in wfmod API and owns only revision metadata. It does not duplicate domain state. WorldSim must not reject/apply based on this partial vector until every snapshot field has an audited canonical writer and a wired lane.


### Character lane wiring — WF-RT-0023

| Canonical owner | Writes observed | Lane | Status |
|---|---|---|---|
| `CharacterRegistry` | lifecycle publication for create, posts, profession/employment, migration, duties, family, death; restore; entity bind/unbind; age/life-stage refresh | `CHARACTERS` | Wired post-write; direct mutable props writers outside registry still require audit |


### Character lane precision follow-up — WF-RT-0023

The CharacterRegistry integration now avoids revision churn for idempotent appointment, identical entity binding, ignored entity unbinding, same-destination migration and repeated unchanged calendar refresh. Entity body-kind normalization is consistent across bind/unbind. The lane remains post-write invalidation; it does not establish a cross-thread lock around mutable Character state.


### Expanded Character writer inventory — WF-RT-0023

The Character domain is not owned exclusively by `CharacterRegistry`. Direct authoritative/derived writes also exist in:
- `PopulationSimulation`: profession, command, training, intent, age/life-stage, death metadata and simulation progress.
- `CharacterDevelopment`: race/class/rarity/level, mood/personality/stress, conduct receipts, growth/traits, upbringing, morale/injury, abilities and observer memory.
- `Courts`: origin race and surname initialization.
- `Succession`: heir traits and title succession metadata.

These writers must be classified before integration. Do not use a blanket observable-map hook: it would count lazy defaults, derived caches and multi-field operations as separate mutations, creating revision churn and duplicate invalidation. Target one canonical transaction boundary per logical operation; preserve existing semantics and public gameplay capabilities.


### Character canonical writer batch — WF-RT-0023

CharacterRegistry.MutationBatch is the shared Character transaction boundary. The outer batch opens one DomainMutationRevisions.CHARACTERS writer scope before mutation; nested batches join it. A dirty outer close commits one lane revision, and snapshots are unavailable during the active scope.

Integrated owners:
- PopulationSimulation: profession, duty, command, needs, skill/training, intent, death and daily population tick.
- CharacterDevelopment: race/class/rarity/level, mood, psychology, memory, injuries, morale and abilities; compound conduct/experience/inheritance/upbringing/combat injury operations.
- CharacterRegistry: hire/fire/migrate/profession/duty/life-stage composite operations.
- Courts.ensure and Succession.onDeath: generation/succession batches.

One daily world population tick and one compound gameplay action now produce one revision, rather than one increment per nested field write. Idempotent setters do not invalidate unchanged snapshots. Remaining direct public mutable Character fields/collections and addon writers still require a complete audit; do not enable a global apply fence yet.


### Registry identity and trait mutation coverage — WF-RT-0023

The Character transaction scope now wraps create/restore, appointment/dismissal, entity binding/unbinding, death, marriage/divorce and child creation. CharacterStats trait add/remove are canonical scoped writes; succession uses CharacterStats for the usurper trait. These scopes prevent revision snapshots during the owning operation and coalesce nested lifecycle changes.

Remaining bypass surface: Character still exposes mutable public fields, children/posts/traits collections and props. External addon direct writers remain under audit. The lane is not yet globally complete.

### CharacterDevelopment.ensurePsychology — canonical writer follow-up

- Owner remains `wf-people.CharacterDevelopment`; this is not a new compute domain or a second psychology store.
- Lazy persistence of deterministic temperament/drive defaults and initial stress now uses the existing nested `CharacterRegistry.MutationBatch` and publishes one `CHARACTERS` lane revision only when state actually changes.
- Repeated initialization is idempotent and does not invalidate snapshots unnecessarily.
- Commit: `fe94acfca57a2510fb27bd3379bb044ee694f739`.
- This closes one identified in-module writer bypass only. Public mutable Character state and external addon writers remain unaudited; do not enable the global WorldSim apply fence.

### Succession political-domain writer follow-up — WF-RT-0023

- `Succession.onDeath` remains the canonical owner of ruler succession; no second succession model or political cache is introduced.
- Its realm ruler and title-holder metadata writes now open the existing `REALMS_POLITICS` lane before mutation, while Character death/posts/heir operations remain under the existing `CHARACTERS` batch.
- The political lane advances once for a changed realm succession and remains unchanged for an identical metadata state. The existing CourtTest now asserts one political revision for one succession operation.
- Source: `2623288d90c29fb67990b4fbf5f72174d6e333a8`; regression assertion: `e246dac4af44b727c549618fe9585a6cf133a3c2`.
- This is a two-domain freshness improvement, not a complete atomic cross-domain transaction or proof that all Realm/Politics writers are covered. Global WorldSim apply fencing remains disabled.

### TitleEditor canonical political writer boundary — WF-RT-0023

- `TitleEditor.group` is now the single canonical `REALMS_POLITICS` writer boundary for map/title/realm edits, including grouped edits and undo/redo. A changed group commits one lane revision; a no-op group does not advance it.
- `Succession.onDeath` routes ruler plus title-holder/succession metadata through that existing group when an editor is available. It must not open a second nested REALMS_POLITICS scope around `TitleEditor`, which would double-count the same logical mutation.
- Headless succession without an editor retains a single direct lane scope. This does not yet provide durable atomicity across CHARACTERS and REALMS_POLITICS.

### Origins ownership audit — WF-RT-0023 follow-up

- `wf-origins.OriginsMod.owner(ServerPlayer)` currently returns `p:<UUID>`; its state is held in `Origins.builds` and persisted through the dedicated `wf_origins.json` save file.
- NPC Character race/class facets are stored by `wf-people.CharacterDevelopment` on the canonical Character (`identity.race`, `rpg.class`). This is a different observed owner path, not sufficient evidence to merge the stores.
- `StatApi` documents both `p:<uuid>` and `n:<id>`, while the Forge Origins adapter currently only exposes player owners. Audit any NPC Origins assignment callers before deciding whether NPC origins should be mirrored or migrated.
- The shared `ORIGINS` lane is now wired to canonical progression writes. `build/peek/assign` expose detached `Build` values; unknown-owner reads do not materialize records. Race/class/retrain/profession operations and validated save restore advance the lane only for real changes. Public methods are synchronized to serialize operations through the single Origins registry. Datapack definition reload remains a distinct lifecycle and must be reconciled with persisted progression before global snapshot fencing.
- Do not introduce a second character identity/progression calculation or enable the global apply fence until this is resolved.

### Origins canonical writer boundary — revision lane and detached reads

- `Origins.Build` is retained as a value shape for compatibility, but public `build(owner)` and `peek(owner)` now return detached copies. Mutating those values cannot bypass the canonical writer API. Reads of unknown owners do not create persistent empty records.
- Internal canonical state is accessed only through private `state(owner)` and `builds` methods. Race selection/reset, class choice, retraining, profession learning/drop/work, deterministic NPC assignment, and validated save restore open the existing `ORIGINS` lane and commit once for a real change.
- Class choice and NPC assignment stage a new Build before publishing it into the canonical map, so a failing archetype hook cannot leave a partially published Origins record.
- Save restore validates a staged map first and skips revision advancement when the restored state is identical.
- Save restore retains syntactically valid unknown race/class/profession IDs as dormant progression. Definitions removed by a datapack reload therefore do not erase history/XP or make the save unloadable; reintroducing the IDs reactivates the stored progression. Invalid shapes, duplicate history IDs, invalid XP ranges and known-definition dependency violations remain rejected.
- **Still open:** `OriginDefs.replaceWith` publishes through several public mutable maps; it is not yet a single immutable definitions snapshot for concurrent readers. Do not claim datapack definition publication is globally atomic. Cross-domain Progress/Character hooks also remain outside a durable multi-domain transaction.
- This closes the known getter-based mutation bypass, not every possible concurrency hazard: `Origins.Build` remains a mutable detached DTO, definitions reload is a separate definitions lifecycle, and cross-domain Progress/Character hooks are not yet a durable atomic transaction.


### TreasuryLedger canonical economy writer boundary — WF-RT-0023

- `TreasuryLedger` now opens the existing `ECONOMY_TRADE` revision lane around canonical account/ledger mutations (`open`, credit-limit changes, deposit/withdrawal, transfer, loan, interest and repayment).
- Nested public operations such as `transferOnce -> transfer` join one thread-local, ledger-instance-scoped mutation batch and publish at most one revision for the logical operation. No-op/idempotent retries do not advance the lane.
- `fromJson` rebuilds indexes and balances in restore mode without publishing live-world revisions.
- This is revision ownership, not a complete immutable ledger snapshot: `Account` still exposes mutable balance/credit/debt fields and remains a known bypass surface. Direct external writes must be audited before snapshot/apply fencing. The ledger journal and existing indexes remain the sole financial record; no parallel cache or executor was added.
- Source commits: `3726bab9`, `5812300a`; regression tests: `1023ded9`. CI must pass before treating this boundary as verified.


### TreasuryLedger concurrent writer serialization — WF-RT-0023

- The canonical mutating public methods on `TreasuryLedger` are now synchronized on the ledger instance. Nested calls remain reentrant and continue to join the existing thread-local `MutationBatch`; this closes the race where concurrent callers could share one thread-local batch and mutate account/journal structures simultaneously.
- The ledger still uses the existing `ECONOMY_TRADE` revision lane and the existing account/journal indexes. No new executor, cache, ledger, or revision coordinator was introduced.
- Added a concurrent regression fixture: four writer threads perform 100 distinct transfers; the expected result is 100 successful transfers, conserved source/destination balances, and exactly 100 economy revisions.
- Direct mutable `Account` exposure remains a bypass risk. Synchronization of canonical methods does not make external field writes safe. Do not enable global snapshot/apply fencing until a read-only account view and external-writer audit are complete.
- Commits: `f48ee6a5`, `af5c18a6`. CI pending.


### Origins runtime prerequisite compatibility audit

- Runtime consumers must treat a missing datapack prerequisite as dormant/unavailable, not dereference a null definition. `Origins.reqProblem` and `Origins.canLearn` now return explicit blocked reasons for missing required professions/classes and missing profession parents; rank indexes are guarded against the current rank list.
- The canonical progression IDs/XP remain in `Origins`; no second progression registry or cached definition index was introduced.
- `OriginDefs` publication is **not yet atomic**. Its public mutable maps and nested mutable record members remain legacy build-time/reload surfaces. The next definition-lifecycle change must introduce one deep-immutable snapshot and make each Origins operation capture exactly one snapshot reference; a shallow map wrapper is not sufficient.
- Regression tests: `OriginsTest` dormant prerequisite cases. Source/test commits: `dfb0a1a1`, `1eec10db`. CI run `43603966100` queued at documentation update time.


## Migration progress — source-audited production ownership (2026-11-14)

This is an operation-count status, not a percentage of all UniverseForge computation. The repository does not yet contain a complete denominator/inventory of every Java compute path, addon writer, and third-party optimizer, so a project-wide completion percentage would be fabricated.

| Status | Operation keys | Count | Evidence / limitation |
|---|---|---:|---|
| Rust owns production computation and is accepted as migrated | `worldsim.emergence.advance`, `worldsim.decide` | 2 | Shared `WorldSimComputeRuntime`/JNI production path. Population parity and save/reload acceptance are still pending, so it is deliberately excluded from this count. |
| Rust production pipeline connected; migration acceptance pending | `worldsim.population.advance` | 1 | Native Rust runs through the same process-wide runtime when available; Java fallback is exclusive. Full parity, save/reload and writer-freshness gates remain open. |
| Rust implementation exists, production ownership unverified | `worldsim.generate` | 1 | JNI wrapper and Rust `worldgen::generate_world` exist; no production caller is evidenced. Do not count as migrated. |
| Java remains the intended owner | `worldsim.snapshot`, `worldsim.materialize.people`, `worldsim.materialize.polities`, `worldsim.proposal.apply` | 4 | These operations cross Minecraft/domain authority boundaries; do not move Minecraft API or authoritative side effects into Rust. |
| Remaining candidate compute groups | economy/trade; logistics/path planning; additional political/diplomatic planning; military/tactical simulation; spatial/geographic calculations | 5 groups for audit, not five guaranteed Rust ports | Each group must be decomposed into actual operations, profiled, checked against installed optimizers and existing indexes, then selectively ported only where pure compute and measured benefit justify it. |

**Current honest headline:** 2 fully accepted Rust-owned production operations; 1 additional Rust-backed Population pipeline connected but not yet accepted; 1 dormant/unverified generation entry point; 5 remaining candidate groups requiring source-level decomposition. This is not a project completion percentage or a guaranteed count of ports. Java/Minecraft integration, canonical mutation, persistence, materialization, rendering and side effects are intentionally not migration targets.



### No-double-compute audit — Population production path (2026-11-15)

- `PeopleMod.onTick` first delegates the world-day interval to the optional `PopulationComputeCoordinator`. If that coordinator returns ownership, the Java fallback is not invoked for that interval.
- When the optional addon is absent, or native execution is unavailable before submission, the coordinator returns control to the exclusive Java fallback. A reflective/linkage failure after coordinator discovery fails closed and holds Java fallback rather than risking concurrent execution.
- Rust and Java do not both apply results for one interval. The parity fixture is test-only and does not mutate production Characters.
- `PopulationSimulation.advanceToWorldDay` remains the Java fallback implementation; it must not be called alongside the native pipeline. The Rust pipeline remains a candidate until parity, save/reload and revision-freshness acceptance gates pass.
- Current CI run `43709019239` exposed one test assertion bug in `DomainMutationRevisionsTest`: it expected the Settlement lane to equal absolute revision 1 even though an earlier successful Settlement mutation had already advanced it. The corrected test compares each lane against its own pre-operation revision. This is a test expectation correction, not a production revision-vector defect.

### `worldsim.emergence.advance` — request-scoped lookup indexes

- Rust remains the sole production compute owner for this operation.
- Reuses one request-local borrowed index for map IDs, geographic coordinates and canonical band IDs; no duplicate geographic store, persistent cache, extra worker, or Java fallback calculation is introduced.
- Replaces repeated linear coordinate lookup and polity-member aggregation scans with indexed lookup.
- Determinism guard: reversed geographic-cell input order must produce an identical `EmergenceFrame`.
- Source commits: `10b20f8d`, `680dfbff`, `33a1bec7`. Await CI and large-map benchmark evidence; no numeric performance claim yet.


### `worldsim.decide` — contested realm membership

- Existing Rust decision owner remains authoritative for proposal computation.
- Recruitment no longer scans every border for each settlement. One request-local ordered set of realm IDs touching contested borders is built from the already-normalized border list and reused for membership checks.
- No persistent cache, parallel decision logic, extra executor, or Java duplicate calculation is introduced.
- Added regression coverage for contested vs. uncontested border recruitment.
- Source commit: `f8e6381e`. CI and large-snapshot profiling pending; no measured performance claim yet.


### `PopulationSimulation` query/write boundary audit

- Read-only APIs now use `readMapProp`, which returns an immutable empty view for absent maps without materializing data.
- Mutation APIs continue to use `mapProp` and the existing Character mutation batch.
- Regression test asserts absent needs/skills/training maps remain absent and registry revision is unchanged after read queries.
- This is a Java correctness repair; it does not count as a Rust-owned production operation. `PopulationSimulation.advance` remains Java-owned and is not duplicated in Rust.
- Source/test commits: `358b28f7`, `c8b28164`. CI pending.


## Shared execution runtime

- `WorldForgeApi.computeRuntime()` is the sole process-wide bounded compute executor for addon domains.
- `WorldSimComputeScheduler` delegates to that runtime; it no longer owns an executor or native semaphore.
- The runtime has one global in-flight/completed-uncollected slot. A result remains in the slot until its operation key polls it, so another domain cannot overlap native work or silently displace a completed result.
- Closing a domain lifecycle does not cancel native code or release the slot early. The caller's world/lifecycle ticket decides whether the eventual result is still applicable.
- This is infrastructure, not another compute operation. The production Rust operation count remains two. Population remains Java-owned pending parity and safe asynchronous apply.

### Population batch compute — Rust candidate implementation (2026-11-14)

- Added pure Rust `population::advance` over immutable Character projections. It mirrors the current Java daily need drift, intent priority, age/life-stage thresholds, profession/duty/command selection, dead-character handling and elapsed-day accumulation.
- Added a dedicated schema-v1 JNI entry and Java native wrapper. Unknown need keys are preserved, missing needs default to zero, duplicate/empty IDs fail closed, and results are sorted by stable Character ID.
- Added native unit tests for multi-day drift, priority ordering, life-stage and role behavior, dead characters, duplicate IDs and schema rejection.
- **Not yet production-owned:** no live Character snapshot adapter, Java/Rust parity harness, asynchronous ticket/apply integration or durable pending-day protocol has been wired. Java remains the sole production owner; do not execute both implementations in production and do not count this candidate as a third migrated production operation.
- Next integration package must capture the canonical immutable Character projection, compare both implementations on identical snapshots, preserve one Java mutation batch, persist/acknowledge elapsed days only after successful apply, and reject stale revision/lifecycle results.


## Population compute bridge — immutable capture and validated apply

- Added deterministic server-thread projection using canonical global Character UUIDs, preserving extension needs and excluding dead Characters.
- Full native-frame validation checks schema, world, year, elapsed days, unique/exact Character IDs, need entries, intent, age/life stage and signed-long day counters before the first write.
- Application is one nested Character mutation batch; age/life-stage proposals use the existing lifecycle event publisher.
- Java long counters use exact decimal-string JSON encoding to avoid precision loss in the lightweight Java JSON parser.
- This is not a production cutover. Public mutable Character state and external addon writers remain an incomplete revision-fence surface; durable pending-day handling and async shared-runtime submission remain open. Production Rust ownership remains two operations.


### Population frame integrity gate

- Captured birth years and prior day counters are retained as immutable apply metadata.
- Apply verifies age/life-stage consistency, exact elapsed-day acknowledgement, year acknowledgement, required needs, and bounded integral values for all need keys before any mutation.
- Invalid frames fail closed before writes; this does not add Java-side duplicate simulation formulas and does not authorize production cutover.


### Population shared-runtime production pipeline

| Operation key | Current production compute owner | Execution | Java responsibility | State |
| `worldsim.population.advance` | Rust when native library is available; otherwise exclusive Java fallback | Existing process-wide `WorldForgeComputeRuntime`, one worker shared with WorldSim | Canonical Character snapshot, per-Character world-day receipt, revision freshness, lifecycle event and state application | Native async pipeline wired; parity and save/reload campaign evidence pending |

- Population does not create its own executor or worker pool. It submits and polls by its own operation key on the existing shared slot.
- wf-people remains independent; wf-worldsim is an optional integration. Java fallback is exclusive and used only when the native provider is unavailable or fails before apply.
- Per-Character `population.last_simulated_world_day` receipts make partially saved/reloaded batches idempotent. The Rust snapshot contains per-person elapsed days and Java's canonical `canWork` eligibility result.
- Rust now follows Java's profession work eligibility gate. Production migration count should be updated only after full parity and save/reload evidence; do not claim full Population completion yet.


### Shared Population parity oracle

- Added `shared/contracts/population-compute-parity-v1.json` as one fixture consumed by both the Java Population logic test and Rust `population::advance` unit test.
- It covers adult work, child eligibility, hero command, hunger/safety priority, adolescent training, explicit training targets, soldier patrol, mixed per-Character elapsed intervals, needs drift and durable day acknowledgements.
- The production operation count remains unchanged until broader parity and persistence gates pass.




## Trade ranking parity boundary audit — 2026-11-16

- Rust `trade::compute` owns only immutable market candidate ranking; Java remains owner of route creation, route caps, escrow, cargo, treasury, stock, shipment delivery, duties and persistence.
- Removed epsilon relaxations from Rust's spread, export-reserve, destination-stock and capacity eligibility comparisons. These now use the same strict thresholds as the Java fallback, so near-boundary values cannot produce different route candidates solely due to provider selection.
- Added Rust boundary coverage for just-below-spread, just-below-export-reserve, just-below-half-stock and just-below-capacity inputs. Exact candidate order remains descending score with stable snapshot traversal tie-break.
- The new parity change is pending Windows/Linux native tests and the targeted addon dependency-closure workflow. Do not mark Trade fully accepted until that run passes and the server-level candidate/route lifecycle is verified.

## Army / Troops production ownership audit — 2026-11-16

### Strategic Army combat

- `ArmyEngine.battles` captures every independent barony battle for the strategic day and calls `ArmyCombatCompute.trySimulateBatch` once with detached roster/type data, deterministic day/location seeds and defender flags. Rust `army::simulate_batch` runs the bounded eight-round `army::simulate` kernel for each battle in stable input order; Java retains canonical roster mutation, casualty application, morale application, war score/events and retreat.
- Provider absence before ownership selects the exclusive Java combat path. A failure after provider discovery fails closed; it does not execute Java combat in parallel.
- Rust tests cover Java-compatible seeded RNG, deterministic output, counter effectiveness and malformed roster/schema rejection. The current pure-Java `ArmyTest` battle scenario does not load `NativeWorldSimulation`, so it exercises the Java fallback rather than proving end-to-end Rust/Java parity.
- The native combat path is production-connected and now batches independent same-day battles into one JNI frame. It is not yet counted as fully accepted until a shared deterministic combat parity fixture and native-path integration test run on Windows/Linux are present.

### Physical Troops compute

- `TroopCompute` invokes the native provider synchronously through JNI. `TroopController.step` reaches morale and formation calculations per live squad during its half-second control step; operational supply calls the same provider from its state-advance path.
- These calls do not use `WorldForgeComputeRuntime`. Morale and supply are constant-time arithmetic; formation planning is bounded by the physical squad size. JNI/JSON overhead may exceed the work being moved, and synchronous calls occur on the server thread.
- Do not count these kernels as an optimization acceptance or expand them to more per-entity operations without a measured crossover. The next decision is to batch genuinely expensive tactical work through the shared runtime, or keep/remove the small kernels from the native production path so there is one clear owner and no pointless JNI overhead.
- Java remains authoritative for entity state, squad orders, canonical Character links, persistence and Minecraft effects.

### Targeted addon validation coverage

- `.github/workflows/army-rust.yml` now includes `wf-army` and `wf-troops` in the dependency-closure Gradle build, in addition to People, Settlements and WorldSim.
- The same run executes the complete pure-Java logic suite and native Rust tests/builds on Windows x64 and Linux x64. This closes a previous validation gap: Java logic tests alone do not compile the Forge `mc/` source sets of Army and Troops.
- This workflow coverage change improves acceptance evidence; it does not itself mark Army/Troops compute as migrated or accepted.

### Population durable restart baseline

- A world-scoped, atomically written `worldforge/wf_population_baseline.json` records the first day on which Population receipts became authoritative. It is initialized once from the loaded world clock and never advances with the process-local cursor.
- Existing legacy Characters without a per-Character receipt catch up from that stable baseline. Newly created Characters receive their current world-day receipt immediately, so they are not simulated for days before they existed.
- Both Rust capture and exclusive Java fallback use the durable baseline for missing receipts. Per-Character receipts still determine each actual elapsed interval, so partially saved registries catch up after restart without replaying already persisted partitions.
- A corrupt baseline or one bound to another world identity fails startup closed instead of silently resetting simulation history.
- Batch-level elapsed metadata remains the maximum positive per-Character interval; each Rust Character receives its own elapsed interval. Empty batches remain unsubmitted.


### Population reload cursor correction

- The process-local PeopleMod cursor is not treated as proof of durable Character application. On server start, a Character partition can restore an older `population.last_simulated_world_day` even when the global clock is current.
- Population now captures at the current world day even when it equals the process-local cursor. Per-Character receipts decide who needs catch-up; legacy Characters without receipts use the current fallback baseline.
- Batch-level elapsed metadata is the maximum positive per-Character interval; each Rust Character still receives its own elapsed interval. Empty batches remain unsubmitted.


### Population durable counter precision

- Character props now persist `population.simulated_days` and `population.last_simulated_world_day` as exact decimal strings, matching the Rust ABI.
- The reader accepts legacy numeric saves and upgrades them to strings on the next authoritative population apply.
- This is required because the repository JSON parser decodes JSON numeric tokens as `Double`, which cannot exactly represent all 64-bit integer values above 2^53.


### Population receipt scan scheduling

- Added a canonical registry-generation signal to wf-people. It advances when the player partition is loaded or a new map CharacterRegistry is registered.
- Native receipt recovery scans only on first server tick after lifecycle change, world-day advance, or registry-generation change. It does not walk every Character every tick.
- Completed batches remember the generation they captured; a registry loaded during compute forces a follow-up scan rather than being hidden by a successful older batch.
- Java fallback uses the same day/generation gate. Per-Character receipts remain the source of truth; the generation is only a scan invalidation signal.


### Origins datapack definition ownership

- `OriginDefs` remains the sole owner of race/class/profession/synergy definitions. Its six mutable builder collections are private; a complete deep-immutable, generation-tagged `Snapshot` is published through one volatile reference.
- `Origins` captures one snapshot for each synchronized logical operation. Datapack reload swaps the OriginDefs publication and Origins active generation under the same owner lock, preventing mixed-definition operations.
- Existing public collection names remain compatibility read views, but are no longer mutable; nested records/maps/lists/perk trees are defensively frozen. No second registry/cache or duplicated definition owner was added.


### Population Rust parity matrix expansion — WF-RT-0024

- Expanded the shared cross-language fixture to a deterministic 60-Character boundary matrix: life-stage boundaries, need/intent thresholds, worker/specialist/soldier/hero roles, command whitespace, training targets, mixed elapsed intervals through 1,000 days, extension-defined needs outside the built-in range, and exact near-i64::MAX simulated-day receipts.
- Input order is deliberately reversed while expected output remains ID-sorted. Rust and Java must agree on the complete projection and stable ordering.
- Java assertions compare the complete needs map, including extension keys, and verify durable year/world-day acknowledgements. Rust executes the same complete fixture.
- This strengthens the existing population parity gate; it does not add a second calculation or count a new production owner. External Character writers and WF-RT-0021 cross-domain atomicity remain cutover blockers.


### Population parity fixture envelope follow-up

- The expanded fixture now uses world day 10,000 consistently at request and expected-frame levels, allowing all 1–1,000 day per-Character receipt intervals to remain nonnegative. CI had caught the expected top-level envelope retaining the old day 303; Rust correctly echoed the request's day.


### Scoped revision dependencies — WF-RT-0023

- DomainMutationRevisions now tracks active writers per domain lane and exposes scoped snapshot(Domain...) / isCurrent(snapshot, Domain...) checks alongside unchanged global methods.
- A projection must name every canonical domain it reads. PopulationComputeBridge now captures and validates only CHARACTERS; unrelated Settlement/Economy/Politics writers no longer starve or stale a result that never read those domains.
- Global WorldSim snapshot/apply fencing remains prohibited until every domain writer and cross-domain transaction is audited. The unscoped API remains conservative and observes every lane.
- Regression tests cover unrelated active/committed writers, relevant active writers, and preservation of global freshness semantics.


## Atlas native routing ownership

| Operation | Current owner | Rust owner | Java responsibility | Status |
|---|---|---|---|---|
| `atlas.surface.explored-route` | Java `ExploredSurfaceRoutePlanner.find` | Rust `surface_route::find` through MAQAN native JNI | Capture the persisted explored-chunk snapshot, validate route endpoints, preserve surface heights and render/execute navigation | Native A* path added with exact mode/biome/slope costs and exclusive Java fallback when native provider is unavailable or fails; Windows/Linux CI and route parity fixtures pending |

The route planner may only traverse chunks already recorded in `NavigatorExplorationStore`; native code receives a detached packed snapshot and never queries or loads Minecraft chunks. The existing `ExplorationIndex::route` remains a separate lower-level Dijkstra API and is not used as a duplicate production route calculation.


### Population CI dependency-closure coverage — 2026-11-15

- The targeted Rust/Java validation workflow now triggers on changes under `wf-people` and the shared Population compute contracts, not only on `wf-worldsim` and native Rust changes.
- The dependency-closure build explicitly targets `wf-people`, `wf-settlements` and `wf-worldsim`; this ensures the canonical Character owner and its population bridge are compiled in the same focused validation run.
- The pure-Java logic suite continues to run in full, including `PopulationSimulationTest`; native tests include the Population module on both Windows and Linux.
- This closes a CI routing/coverage gap only. It does not mark `worldsim.population.advance` accepted: parity, restart/save-reload and revision-freshness campaign gates remain open until evidence is recorded.

### Population completion generation fence — 2026-11-15

- PopulationComputeCoordinator now compares the generation captured with each pending Rust batch against the live PeopleMod.populationRegistryGeneration() before accepting its result.
- A map/player Character partition registered while native compute is in flight invalidates that result even if the new partition currently contains no alive Character IDs. The coordinator discards the frame and captures a fresh canonical snapshot on the next tick; per-Character receipts prevent already-applied work from being repeated.
- The generation is a scan/freshness fence, not a second identity index or revision owner. The scoped CHARACTERS mutation revision and exact live UUID-set check remain in PopulationComputeBridge.apply.
- Added .github/workflows/army-rust.yml to its own push path filters so edits to the targeted validation workflow trigger the validation run.
- Acceptance remains pending until the targeted Windows/Linux Rust tests, Java regression suite and People/Settlements/WorldSim dependency-closure build pass.

### Population baseline recovery fix — 2026-11-16

- Closed the restart gap where the process cursor was initialized to the current world day and therefore caused legacy Characters without receipts to be treated as already simulated.
- `PopulationWorldBaseline` persists the first authoritative world day atomically in the world save and validates its world identity on reload.
- `CharacterRegistry.create` stamps newly created Characters with their creation-day receipt; old persisted Characters without receipts use the stable world baseline.
- Rust coordinator and Java fallback now use the same durable baseline. The process cursor is only a scheduling/progress hint, never the fallback origin.
- Added regression coverage for missing receipts, baseline reload stability, and cross-world mismatch rejection. Acceptance remains pending targeted CI and an actual server crash/restart campaign.

### TradeNetwork mutation boundary — 2026-11-16

- `TradeNetwork` now publishes the shared `ECONOMY_TRADE` lane for canonical route creation/removal, customs-duty changes and valid simulation ticks.
- Nested autonomous route creation joins the outer network mutation batch; one logical review publishes at most one revision. Idempotent duty updates and invalid/non-positive ticks do not publish a revision.
- The network batch owns only route/shipment state. Financial account postings continue to publish through `TreasuryLedger` as the sole journal/balance owner; no second ledger revision or financial calculation was introduced.
- Added focused regression assertions for create, remove, changed/no-op customs policy, valid simulation tick and invalid tick.
- **Still incomplete:** `routes`, `shipments` and `events` remain public mutable collections; `Route` and `Shipment` expose mutable fields. Direct external writes can bypass the lane. The tick currently conservatively invalidates when active network state exists; it does not yet provide a deep immutable network snapshot. Do not enable global WorldSim apply fencing or claim `ECONOMY_TRADE` complete until these bypasses and Settlement writers are closed.

### Settlement simulation mutation boundary — 2026-11-16

- `Settlement.tick` now runs under one nested `SETTLEMENTS` mutation batch. The whole bounded simulation tick (needs, economy, growth, construction, production, hauling, raids and worker movement) invalidates one revision rather than publishing per sub-step.
- Canonical resident-count changes, world-calendar changes, worker creation/removal and placement requests use the same lane; nested `placeOnce -> place` publishes once. Idempotent resident/calendar writes do not advance the lane.
- Treasury postings continue to publish `ECONOMY_TRADE` through `TreasuryLedger`; Settlement owns its simulation/projection lane and does not duplicate the financial journal.
- Added regression coverage for valid/invalid tick, resident-count no-op, and worker creation.
- **Still incomplete:** direct public `stock`, `buildings`, `workers`, `flow`, `taskGraph` and mutable domain fields remain bypass surfaces. Several direct order and lifecycle writers (including external Character reconciliation and transfer projections) still need canonical scopes. Do not treat `SETTLEMENTS` as globally complete or enable global WorldSim apply fencing.

### Settlement command/lifecycle write coverage — 2026-11-16

- Public settlement command APIs for worker assignment/unassignment, profession changes, manual movement/waypoint completion, hauling assignment, batch assignment, building pause/priority/cancel/adoption, departure acknowledgement, and work-priority updates now enter the shared nested `SETTLEMENTS` mutation scope.
- Nested batch commands and lifecycle operations coalesce into one revision per outer logical command; the tick remains a single bounded revision.
- Added focused revision assertions for manual worker orders and work-priority changes.
- **Not closed yet:** economic/resource transfer methods mutate TreasuryLedger and Settlement mirrors; direct public state (`stock`, `workers`, `buildings`, `flow`, `taskGraph`, policy fields and mutable Worker/Building objects) remains externally writable. External CharacterRegistry reconciliation and trade delivery paths need an explicit ownership contract. This is partial writer coverage, not a safe global snapshot fence.

### Settlement economy controls — 2026-11-16

- Settlement-level tax policy, auto-trade policy, external market-demand shifts and manual buy/sell commands now enter the same nested `SETTLEMENTS` mutation boundary. Internal auto-trading during `Settlement.tick` joins the existing tick scope rather than publishing a separate settlement revision per order.
- Treasury postings remain independently owned by `TreasuryLedger` and its `ECONOMY_TRADE` lane. A market transaction can therefore invalidate both projections, without duplicating the account journal.
- Added regression assertions for changed/no-op tax policy, auto-trade policy and external demand changes.
- **Remaining bypasses:** public mutable collections/fields and mutable Worker/Building objects; resource transfers and CharacterRegistry reconciliation still require an explicit cross-domain writer protocol. Do not enable global WorldSim apply fencing based on this partial coverage.

### Settlement resource and projection boundaries — 2026-11-16

- Settlement mutation nesting is now tracked per Settlement instance on a thread, allowing a single cross-settlement transfer to hold both SETTLEMENTS writer scopes without rejecting the recipient as a foreign nested writer.
- Canonical resource reads (`amount`) now fence lazy ledger-account creation, legacy stock reconciliation and stock-mirror synchronization. Resource additions/consumption, founding endowments, spend/spendOnce, external account transfers, silver transfers and trade-flow projection writes are routed through Settlement mutation scopes.
- Cross-settlement commodity transfers hold both source and destination SETTLEMENTS scopes while the TreasuryLedger publishes its own ECONOMY_TRADE mutation. Trade shipment arrival now records destination trade flow through the Settlement API rather than writing `Flow` directly.
- **Still not closed:** public mutable `stock`, `workers`, `buildings`, `flow`, `taskGraph`, policy/identity fields and mutable Worker/Building properties remain escape hatches. `Flow` and `ResidentTaskGraph` still expose public mutation methods; direct callers can bypass the Settlement fence. TreasuryLedger.Account still exposes mutable balance/debt/credit-limit/bankruptcy fields. External CharacterRegistry reconciliation, world-save ordering, and shipment lifecycle cross-domain atomicity remain under audit. Do not enable the global WorldSim apply fence.

- 2026-11-16 follow-up: dead canonical residents are now removed from the Settlement projection through `removeWorkerProjectionByPerson` (without creating a second lifecycle departure); realm tax remittance now reconciles `remittedSilver` and the silver mirror through `applyTreasuryProjection`. Direct adapter writes for these two paths are closed. Public mutable Settlement collections/fields, direct Flow/ResidentTaskGraph access, and remaining Worker/Building mutation paths are still open audit items; the global WorldSim apply fence remains disabled.
