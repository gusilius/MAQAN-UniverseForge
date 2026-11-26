# MAQAN six pillars — implementation status and integration gaps

This document is an engineering status report, not a feature-completion claim. “Implemented” means a concrete domain mechanism exists; it does not imply every runtime adapter, UI path or multiplayer scenario is complete.

## 1. NPC Intelligence

**Existing:** `PopulationSimulation` owns canonical Character needs, professions, duties, skills and daily intent. `LifeSimulation` supplies utility scoring, emergency behavior selection, bounded GOAP goal selection and LOD scheduling.

**Extended in this pass:** execution now attempts at most three ranked actions. BLOCKED/RETRY results retain the executor's failure reason in bounded Character history, add a temporary per-action cooldown, and trigger an alternative. Need/skill effects are applied only after SUCCESS.

**Still missing:** a single domain-action registry shared by residents, travelling NPCs, soldiers and heroes; structured world-state preconditions/effects beyond the current small operator set; pathfinding/material/tool/permission failure reasons from every concrete executor; long-horizon replanning and simulation load tests at population scale.

## 2. Character Development

**Existing:** stable Character IDs, family/lifecycle records, profession and skill history, CharacterStats, personality traits and RPG progression integration.

**Extended in this pass:** added `CharacterDevelopment`, a single Character-backed facade for data-driven race/class IDs, rarity, level, temporary mood with world-clock expiry, mood-adjusted effective attributes and learned abilities.

**Still missing:** race definition registry with actual racial stat/ability/appearance/aging rules; class trees and specialization unlocks; rarity generation/upgrade rules; ability execution/cooldowns/costs; UI and save migration coverage for full development workflows. The new facade does not itself implement racial gameplay.

## 3. Equipment & Crafting

**Existing:** Character-backed resident tool NBT, profession/skill/level/class constrained crafting, real settlement resource consumption, player-supplied tools, durability wear and autonomous tool maintenance.

**Extended in this pass:** material-backed full repair for damaged resident tools; autonomous maintenance attempts repair before crafting a replacement; ruler command action `/wfresident repair <entity-uuid>`.

**Still missing:** one inventory/item-instance authority shared by residents, soldiers, player, settlement storage and crafting stations; generic recipe/blueprint system; specialist work orders for complex equipment; crafting queues, quality/affixes, repair benches and combat equipment projection for the same Character.

## 4. Military Life

**Existing:** army/corps/squad hierarchy, Character IDs for commanders, individual troop XP/ranks, operational supply ledger, readiness and morale/rout calculations.

**Still missing:** complete individual soldier Character ownership for every troop instance; durable per-soldier equipment/injury/service history; supply demand reserved against settlement/realm stock and routed through logistics; discipline/desertion/loyalty and morale propagation from personality, family, leadership, pay, losses and victories. Current supply and morale primitives are not yet a complete campaign simulation.

## 5. Social Intelligence

**Existing:** Character traits, reciprocal resident relationship scores, Relations affinity/love and cooldowns, social requests, quests, trade and diplomacy actions exposed through dialogue bridges.

**Extended in this pass:** semantic memory facts and records (promise/request/grievance), later-line recall via `{memory:key}`, open/resolved checks, bounded history, opt-in NPC-initiated follow-up conversations, subject/intent context, per-character/player cooldown and speaker-distance enforcement.

**Still missing:** full memory retrieval/ranking by relevance and decay; autonomous conversation content authored per role/culture/personality; robust negotiation/argument/de-escalation; event-driven fulfillment of promises and requests; social actions for NPC-to-NPC interaction at scale. A memory record is not itself proof that a delivery, trade or quest completed.

## 6. World Simulation

**Existing:** settlement task graph with resource provenance, resident work orders and construction, shared population lifecycle, trade markets, quests, army hierarchy, supply/morale primitives and realm/relationship systems.

**Still missing:** a canonical world event bus with durable event IDs, ordering, idempotent consumers and replay; explicit transactions connecting settlement stock → crafting → personal inventory → army requisition → campaign consumption; unified ownership/authority rules for player, realm, settlement and character assets; deterministic cross-domain save/load and regional/remote simulation tests.

## Integration invariants

1. CharacterRegistry owns person identity. Entities, workers, soldiers, commanders and dialogue speakers are projections or roles of that Character.
2. Race, class, rarity, level, profession, duty, personality and mood are independent dimensions.
3. Domain state changes only after the owning subsystem confirms the operation.
4. Failed actions do not grant resources, satisfy needs or complete promises.
5. Dialogue text may describe an action; only the relevant quest/trade/settlement/relations/military API can commit it.
6. Abstract remote simulation must not claim physical navigation, combat animation or block placement that the runtime did not execute.

## Verification boundary

This pass adds logic tests for character development, semantic dialogue memory and NPC fallback decisions. The current GitHub Actions run must finish before the changes can be called build-verified. No in-game Forge client/server playtest is claimed by this document.
