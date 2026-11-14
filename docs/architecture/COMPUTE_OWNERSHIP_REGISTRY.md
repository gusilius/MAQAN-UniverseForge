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
| Rust owns production computation | `worldsim.emergence.advance`, `worldsim.decide` | 2 | Both enter through the shared `WorldSimComputeScheduler` and JNI; Java remains snapshot/apply/materialization boundary. Campaign catch-up and parity evidence remain open. |
| Rust implementation exists, production ownership unverified | `worldsim.generate` | 1 | JNI wrapper and Rust `worldgen::generate_world` exist; current source audit found no production caller. Do not count as migrated. |
| Java remains the intended owner | `worldsim.snapshot`, `worldsim.materialize.people`, `worldsim.materialize.polities`, `worldsim.proposal.apply` | 4 | These operations cross Minecraft/domain authority boundaries; do not move Minecraft API or authoritative side effects into Rust. |
| Remaining compute domains | population micro-simulation, economy/trade, logistics/path planning, political/diplomatic planning, military/tactical simulation, etc. | Not yet exhaustively counted | Target ownership in the migration contract is not evidence of implementation. Each domain needs source-call-site inventory, profile, parity fixtures, one-owner cutover, and addon compatibility audit. |

**Current honest headline:** 2 confirmed Rust-owned production compute operations; 1 unverified Rust entry point; no defensible overall percentage yet. The next progress report must increase the count only after a production cutover is evidenced in source and CI/parity checks, not when a Rust function or wrapper is merely added.


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
