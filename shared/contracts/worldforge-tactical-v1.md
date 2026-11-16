# WorldForge Tactical Compute Contract v1

Status: draft domain contract; Rust pure-function implementation started. JNI/runtime integration is not yet enabled.

## Purpose

Provide deterministic, scale-independent tactical recommendations for a selected squad or formation. The same contract must serve RTS single-unit/squad control and large Total-War-scale engagements. It is a proposal engine, not an autonomous world owner.

## Ownership boundaries

- `wf-people` owns canonical Character records, global Character UUIDs, lifecycle and identity authority.
- `wf-troops` owns physical troop entities, canonical squad membership, player orders, formations, command hierarchy, command permissions and authoritative application.
- `wf-army` owns strategic army records, campaign-level strength, logistics and strategic supply.
- `wf-settlements` owns worker/profession/building/work-area state. A worker changing duty to soldier remains the same Character; do not create a second identity.
- Rust `worldforge-sim::tactical` consumes only copied immutable numeric/enumerated snapshots and returns a proposal. It never accesses Forge, entities, registries, networking, save data, or UI.
- The MAQAN UI is a view/controller over these owners. It must not keep a second authoritative roster or invent corps membership from entity fields.

## Identity and hierarchy

- Character identity is `Character.globalId` (world-wide UUID), not Minecraft Entity UUID and not the legacy map-local Character ID.
- Physical entity UUID identifies a replaceable body only.
- Squad IDs are the canonical `Squads` keys.
- Strategic army IDs are owned by `wf-army`; `ArmyHierarchy` maps those IDs to corps and squad IDs.
- Hierarchy order is Army -> Corps -> Squad -> Character. A soldier without an assigned corps/army remains a valid squad/character and must not be silently dropped.
- Optional addons remain optional. If `wf_people`, `wf_army`, or settlement integration is absent, the UI and command layer degrade explicitly; they must not fabricate records.

## Batch evaluation

- Rust exposes `evaluate_tactical_batch` as a deterministic ordered map over independent squad snapshots.
- Batch input order is preserved; each decision echoes only its own source revision.
- A batch is a transport/per-call optimization, not a combined force calculation. Never sum unrelated squads or merge their command policies.
- Java must validate each result independently against the current squad revision, hierarchy, commander identity, permissions, objective and target before applying it.
- The native adapter should cap batch size and split oversized requests without changing snapshot order. A batch must be captured from a coherent server-thread view and must not retain Minecraft objects.

## Snapshot v1

Java constructs a bounded immutable snapshot on the server thread. A batch may contain one squad or several independent squad snapshots; never aggregate unrelated command scopes into one decision.

Required fields:

- `schema_version = 1`
- `world_id`, `simulation_tick`, `request_id`
- `source_revision` for squad/order/formation/command policy state
- stable `squad_id`, `army_id` (optional), `corps_id` (optional)
- commander `global_character_id` (optional) and a host-resolved, sanitized leadership/trait profile
- current order and objective identifier/coordinates in documented world units
- command mode: `PLAYER_DIRECT`, `DELEGATED`, or `AUTONOMOUS`
- permissions: engagement, pursuit, retreat, reform, reposition and objective change
- aggregate troop count/strength, morale, supply, cohesion, formation and flank pressure
- visible threat summaries only; no raw Minecraft entity references or unbounded entity lists
- objective distance and leash in blocks

All IDs are stable strings; all collections are sorted before batching. Numeric ranges and units must be validated before native invocation.

## Result v1

Each proposal echoes `schema_version`, `world_id`, `simulation_tick`, `request_id`, `squad_id` and exact `source_revision`. It returns one action, priority, confidence, advisory flag, reason code and optional bounded target/position reference from the input snapshot.

Supported initial actions: maintain order, reform, engage visible threat, pursue, retreat, reposition. Later versions may add formation selection, reserve allocation, multi-squad coordination, terrain and logistics forecasts.

## Authority and apply rules

1. `PLAYER_DIRECT` produces advisory-only output; Rust must never replace a player order.
2. Delegated/autonomous modes are read from the effective Java `CommandDelegation` policy.
3. Rust permissions are advisory input only. Java re-checks current command authority and every permission immediately before applying.
4. Reject stale source revisions, changed commanders, changed policy, missing/dead Characters, invalid squad membership, unloaded/invalid targets and out-of-world coordinates.
5. Rust never chooses or validates who is authorized to command.
6. Java applies accepted proposals through existing `Squads`, `ArmyHierarchy`, `Formations` and `TroopController` paths. No second order queue or entity controller.
7. A player reclaiming command invalidates pending proposals for that scope immediately.
8. One failed or unavailable native call falls back to safe existing Java order execution; it must not invent an AI order.

## Scale and performance

- Tactical calculation is aggregate and bounded; do not require one Rust call per soldier.
- Single-soldier selection remains a UI/command feature. Squad/formation tactical decisions use aggregate snapshots.
- Large battles should be partitioned by command scope and processed in deterministic batches; army/corps coordination is a later layer above squad decisions.
- Recompute only on relevant revision changes, order changes, periodic simulation cadence, or meaningful threat changes. Do not create a busy loop or a second scheduler.
- Use the existing WorldForge simulation scheduling/runtime path when integration is introduced. First audit its actual presence on the target branch; do not copy scheduler implementations from another branch.

## Compatibility with other addons

- `wf-people`: resolve and validate commander by global Character UUID and lifecycle state.
- `wf-army`: strategic army identity, supply and campaign orders remain authoritative there.
- `wf-settlements`: profession, employment and worker task changes remain in the settlement owner; tactical layer reads duty/availability snapshots only.
- `wf-orders`: diplomacy/realm orders must not be bypassed by a tactical proposal.
- `wf-war`, `wf-relations`, `wf-reputation`, `wf-court`, `wf-quests` and `wf-dialogue`: combat, command changes, deaths and duty transitions must continue through existing lifecycle/event owners. Tactical compute emits no direct side effects.
- Optional module absence is represented as unavailable data, never as invented zero-valued authority or identity.

## Current implementation boundary

The pure Rust function `worldforge_sim::tactical::evaluate_tactical` implements an initial deterministic, single-squad decision slice and unit tests. It is not yet called by Java, does not have JNI, does not apply orders, does not coordinate multiple squads, and does not claim to be a complete commander AI. The existing Java command model remains authoritative until a separately reviewed adapter and runtime integration are added.
