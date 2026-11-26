> **Исторический снимок:** этот аудит составлен для feature/aaa-workspace-2-0-stage-xii и отражает более ранний срез до объединения PR #19–#21. Некоторые выводы о per-map ID, отдельной ArmyEngine gold-копии и ручном Atlas export были исправлены последующими интеграциями. Текущую проверенную картину и оставшиеся разрывы см. в [аудите mainline](MAQAN_MAINLINE_UNIFIED_GAME_AUDIT_RU.md).

---

# MAQAN Cross-System Integration Audit

Audit target: `feature/aaa-workspace-2-0-stage-xii` at the Stage XII PR branch head.
Purpose: record concrete source-level evidence before changing authoritative data models. Status is intentionally conservative: existing partial implementations are not presented as complete world-wide integration.

## Executive result

Five architecture rules are documented in `MAQAN_ARCHITECTURE_INVARIANTS.md`. The current source tree contains useful foundations, but several are not yet connected to the required authority boundary. The most important confirmed gaps are: CharacterRegistry instances are keyed per MapData; settlement Worker has its own numeric identity and optional Character link; LifeSimulation is not wired into the PeopleMod tick path; ArmyEngine maintains its own per-realm gold map; and Atlas reads a manually exported WorldForge JSON snapshot.

## Evidence-based findings

### 1. Character identity — PARTIAL / BLOCKING WORLD-WIDE REGISTRY

Existing foundations:
- `wf-people/Character.java` is a durable person record; its comment says the Minecraft mob body is only a representation.
- `wf-people/CharacterRegistry.java` manages character identity, roles/posts, lifecycle events, hiring/firing/migration and JSON persistence.
- `PopulationSimulation.java` stores profession, duties, needs and skills on the Character.
- `wf-people/WFNpc.java` stores `characterId` and explicitly treats the body as a visual representation.
- `wf-settlements/Worker.java` has a `person` link and `CharacterLifecycleIntegrationTest` verifies a departure by Character ID.

Confirmed gap:
- `PeopleMod.REGS` is `Map<MapData, CharacterRegistry>`, and `registry(MapData)` creates/loads a separate registry for each map.
- `wf_people.json` is stored under `EditorManager.overlayDir(server, map)` for each map.
- Character IDs are generated locally as `c1`, `c2`, ... in each CharacterRegistry. `findNpc` must scan loaded maps to find an ID.
- `Worker` still has a separate integer `id`, its own name/profession and an optional `person` string. The lifecycle integration test manually sets `worker.person = "c42"`; it does not prove every worker is backed by a canonical Character.

Required next work:
- Establish WorldId and one world-scoped CharacterRegistry; stable CharacterId must not collide across maps.
- Namespace role/place references with stable Atlas map/region IDs where necessary.
- Migrate settlement workers, military units, court roles, families and hero progression to references/roles on Character, not parallel people.
- Add migration from per-map `wf_people.json` files with duplicate-ID reconciliation and explicit conflict reports. Never silently merge same-name characters.

### 2. Population and court AI — PARTIAL / EXECUTOR NOT WIRED

Existing foundations:
- `PopulationSimulation` models needs, duties, professions, skill ranks and deterministic intent selection.
- `LifeSimulation` provides utility scoring, emergency behavior-tree selection, a bounded GOAP planner and an Executor callback.
- `PopulationSimulation.completeIntent` applies need changes only after an owning system confirms success.

Confirmed gap:
- `PeopleMod.onTick` calls `PopulationSimulation.advance` for each registry.
- `PopulationSimulation.advance` updates needs and records `updateIntent`; its own documentation says it emits intent only and does not execute work.
- A source scan of the current `wf-people` Java files found no call to `LifeSimulation.tick` from the runtime integration path.
- `WFNpc.registerGoals` uses FloatGoal, LookAtPlayerGoal, a bounded random stroll goal and RandomLookAroundGoal; this is not the court/population task executor.

Required next work:
- Implement authoritative action adapters for eat/rest/safety/social/work/train/patrol/follow-command.
- Feed `LifeSimulation.tick` a real world context and executor; executor must call settlement, inventory, movement, military or social owner services.
- Return accepted/committed/rejected/failed outcomes and apply need/skill effects only from confirmed receipts.
- Cover court NPCs, settlement population and off-settlement population with the same Character identity and simulation contract.

### 3. Economy and resources — PARTIAL / MULTIPLE ECONOMIC STORES

Existing foundations:
- `TreasuryLedger` is a world-wide double-entry ledger with stable accounts, transaction references, debt/credit limits and journal replay data.
- `TreasuryService` exposes the current canonical ledger.
- `TreasuryApi` is a compatibility facade that converts realm gold to ledger silver and derives realm ledger summaries from the journal.
- `Settlement` routes resource accounts through `TreasuryService`.
- `wf-trade/TradeNetwork` settles caravan trade through treasury account transfers.

Confirmed gap:
- `ArmyEngine` retains `Map<String, Double> gold` and mutates realm balances in its own `upkeep` calculation (`gold.put`); `wf-army/Economy.java` explicitly says gold is stored by ArmyEngine.
- This means military income/upkeep is not yet proven to post through the canonical TreasuryLedger.
- `ClockService` retains a legacy summary-file path at world root `wf_treasury.json`; the canonical double-entry ledger is loaded/saved by `WorldForgeMod` under `worldforge/wf_treasury.json`. The legacy `TreasuryApi` summary is a compatibility projection, not the double-entry source of truth.
- Settlement stockpiles, realm treasuries and ArmyEngine gold still need an audited owner/account migration and replay-safe transaction references.

Required next work:
- Route army income, upkeep, recruitment, mercenary costs, loot, tribute and war costs through canonical TreasuryService accounts.
- Make all economic events use unique stable transaction IDs (not repeated category-only references).
- Define one owner and currency/unit conversion per account; balances are projections of ledger entries.
- Migrate old army gold and legacy treasury summary data once, with checksums/reconciliation and no destructive overwrite.
- Add crash/restart/replay tests for every producer and consumer.

### 4. Calendar and time — PARTIAL / CENTRAL CLOCK EXISTS

Existing foundation:
- `WorldForgeApi.clock()` exposes a shared `GameClock`; `ClockService` advances and persists it; `PeopleMod` consumes elapsed days through `pull("wf-people")`.

Remaining audit:
- Verify every simulation module consumes the same GameClock rather than local day counters or independent elapsed time.
- `ArmyEngine` maintains `war.day` and its own `dayAccumulator`; prove this is derived from GameClock and cannot drift or advance while the world clock is paused.
- Settlement time and seasonal progression must be audited against the same authoritative world clock.

### 5. Atlas geography — PARTIAL / EXPLICIT EXPORT SNAPSHOT

Existing foundations:
- `WorldForgeMod` has authoritative map data and `MapSync` supports full-state and delta packet encoding for WorldForge map state.
- Atlas `WorldForgeMapSnapshot` has schema versioning and typed terrain, territories, boundaries and features.
- Snapshot validation is bounded and rejects invalid data while retaining the last known-good import.

Confirmed gap:
- `NavigatorWorldForgeMapStore` explicitly documents itself as a read-only client cache, not a live server transport.
- It reads `config/maqan/worldforge/map_snapshot.json` and reloads from a file.
- WorldForge exposes `/worldforge export`, which writes the Atlas snapshot.
- Thus the current Atlas bridge remains manual export/import; `MapSync` is WorldForge map sync, not evidence of live WorldForge-to-Atlas spatial synchronization.

Required next work:
- Define Atlas-owned stable world/map/region/spatial IDs and coordinate reference contracts.
- Add a versioned server-authoritative Atlas spatial service/transport; publish WorldForge settlement, territory, army and feature overlays as deltas.
- Replace the file reload as the primary path; retain export only for backup/interchange.
- Make all selections, inspectors and layers resolve the same Atlas IDs and coordinate transforms.

### 6. Platform provider integration — PARTIAL / BRIDGE API WITHOUT COMPLETE MODULE REGISTRATION

Existing foundations:
- `CapabilityRegistry` lists discoverable capabilities for Atlas and WorldForge feature mods.
- `PlatformWorkspaceBridge` provides reflection-only APIs for selection, search opener, inspector, actions and map layers.
- `WorkspaceRuntime` supports indexing, inspector providers, context actions, shortcuts, component descriptors, event listeners and provider cleanup.

Confirmed gap:
- `PlatformWorkspaceBridge.registerAction` currently registers `visibleWhen` as always true and wraps a `Runnable` that ignores the selected entity. This is not sufficient authority/selection validation for domain mutations.
- `WorkspaceRuntime.Action.execute` is a `Consumer<Selection>` and returns only boolean registration/execution success; there is no typed domain command receipt/result in this contract.
- `CapabilityRegistry` built-in declarations provide screen class names, but declarations and installed-mod detection do not prove that each module registers real search/inspector/action/view contributions.
- Provider lifecycle cleanup exists for several runtime registries, but subscriptions and map-layer lifecycle need to be audited end-to-end.

Required next work:
- Replace fire-and-forget domain action bridge with typed command requests/results and provider-side authorization/precondition checks.
- Add versioned provider registration and real view factories for every installed module.
- Register full entity search sources, inspectors, map layers and context actions from owning services.
- Test provider load/unload and ensure stale selections, indexes, actions, shortcuts, layers and listeners are removed.

### 7. RTS task execution — PARTIAL / COMMAND GENERATION IS NOT COMMIT RECEIPT

Existing foundation:
- `wf-rts/Orders` builds settlement command strings and pre-validates selection/order plans.
- `wf-rts/Dispatch` applies commands to a Settlement in local/test execution.
- Settlement owns worker/building/resource simulation.

Confirmed gap:
- `Orders.Result` contains strings to send to the server; its own documentation says it does not mutate Settlement.
- A planned command or command string is not a durable task ID, accepted action, committed mutation or execution receipt.
- `Worker` owns a separate manual order queue and position state; shared cross-domain worker/soldier/hero task graph is not proven.

Required next work:
- Create one typed action/task envelope with stable task ID, idempotency key, actor CharacterId, target/resource IDs, authority, preconditions and expected revisions.
- Make domain owners validate and commit commands; return durable receipt with actual resource deltas and failure reason.
- Migrate worker, specialist, soldier and hero orders to this execution service; preserve role-specific behavior as adapters.

## Required implementation order

1. WorldId + world-wide CharacterId registry and migration.
2. Canonical character roles/needs/skills and eliminate duplicate worker identity.
3. Typed task/action execution and receipt contract; wire Population/Court AI and RTS through domain owners.
4. Canonical economy: migrate ArmyEngine gold and all settlement/trade/realm flows into TreasuryLedger; reconcile legacy files.
5. One GameClock for all simulations; eliminate local clock drift.
6. Atlas canonical spatial IDs and live WorldForge overlays.
7. Typed Platform provider lifecycle, full-world search and universal inspector.
8. Shared component migration, embedded view factories, durable notifications, workspace lifecycle and responsive QA.

## Release rule

Stages VII–XII remain open until each gap above has implementation evidence and end-to-end tests. This audit is source evidence and planning, not a claim that these integrations have already been implemented.