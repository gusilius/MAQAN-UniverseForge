# MAQAN Modular Product Architecture

**Status:** accepted target architecture; implementation is staged and source-audited  
**Repository:** `gusilius/MAQAN-UniverseForge`  
**Working branch:** `feature/autonomous-world-simulation-engine`  
**Decision date:** 2026-11-14

## 1. Product decision

MAQAN is one ecosystem made of six separately installable gameplay products. They share stable contracts and may be installed in combinations; they are not six unrelated games and must not become one mandatory monolithic mod.

1. **MAQAN NPC** — canonical persistent people and supported living entities.
2. **MAQAN Politics** — realms, governments, dynasties, institutions, diplomacy and strategic war.
3. **MAQAN RTS Battle** — armies, squads, commanders, tactical combat and battle control.
4. **MAQAN RTS Settlements** — settlements, cities, construction, work, production, storage, logistics and local economy.
5. **MAQAN RPG** — player character, classes, skills, abilities, progression, quests and first-person interaction/control.
6. **MAQAN Simulation** — the independent world-life coordinator that advances the world and orchestrates simulation work exposed by installed domains.

WorldForge Core remains shared infrastructure/API, not a seventh gameplay product. Atlas and the MAQAN platform/UI remain specialized infrastructure/products and are not folded into these six domains.

## 2. Simulation is a separate product and the world-life coordinator

Simulation is not a subfeature owned by NPC, Politics, Battle or Settlements. It is a separately installable mod (the existing `wf-worldsim` is the starting implementation) responsible for making the combined world advance coherently.

Simulation owns:
- world simulation clock integration and deterministic phase scheduling;
- orchestration of domain simulation participants and their dependency graph;
- elapsed-time catch-up, persisted progress cursors and bounded work scheduling;
- cross-domain snapshot barriers, revision/freshness checks and apply coordination;
- event ordering, lifecycle epochs, checkpoint coordination and recovery orchestration;
- global simulation diagnostics, backlog/age metrics and campaign observability.

Simulation does **not** own duplicate copies of Character, Realm, Army, Settlement, Treasury, Inventory, quest or other domain state. It does not reimplement each domain's rules. Each domain remains the canonical owner of its data, invariants and domain operations; it registers a versioned participant/adapter contract with Simulation. Simulation decides when a participant is scheduled and coordinates dependencies; the participant computes or prepares domain-specific work, and the authoritative domain API validates and applies it.

The same operation must have one production compute owner and one scheduling path. A domain must not keep a second autonomous tick for an operation after registering that operation with Simulation. Direct player commands and local real-time mechanics may remain in their domain where they are not the same scheduled world-simulation operation.

When Simulation is absent, independently usable domain features may still support direct commands and local gameplay. They must not silently start a second, reduced world simulator. Full autonomous world evolution is available when Simulation and the relevant domain participants are installed.

## 3. Product ownership and current addon mapping

The existing Forge addon projects are implementation units, not the final product boundaries. Keep their current mod IDs, save namespaces and resource IDs during migration. Consolidation into product artifacts is a later packaging decision, not a prerequisite for domain refactoring.

| MAQAN product | Existing implementation units to audit and consolidate | Canonical responsibility |
|---|---|---|
| NPC | `wf-people`, NPC-facing parts of `wf-origins`, `wf-relations`, character social-memory/reputation facts | Character UUID and lifecycle, names, species/race identity, family, kinship, relationships, needs, health, death, inheritance, identity-preserving roles and external-entity adapters |
| Politics | `wf-realms`, `wf-court`, strategic part of `wf-war`, `wf-orders`, faction/diplomatic part of `wf-reputation` | Realm/government, titles, dynasty and court institutions, laws, diplomacy, factions, political decisions, treaties, casus belli and strategic war |
| RTS Battle | `wf-army`, `wf-troops`, tactical/battle part of `wf-war`, battle-control part of `wf-rts` | Army/corps/squad hierarchy, roster references, recruitment/muster, commanders, tactical orders, formations, combat, morale, supply, wounds and casualties |
| RTS Settlements | `wf-settlements`, `wf-trade`, settlement-control part of `wf-rts` | Settlements/cities, building sites and blueprints, work zones, tasks, production, storage, markets, routes, logistics and local economy |
| RPG | `wf-progress`, `wf-genesis`, `wf-quests`, `wf-dialogue`, `wf-forms`, player-facing class/skill/profession parts of `wf-origins` and `wf-relations` | Player-character creation and progression, classes, abilities, skills, player professions, quests, dialogue-driven play and first-person command surfaces |
| Simulation | `wf-worldsim` plus future participant adapters/contracts | Global clock, orchestration, scheduling, catch-up, freshness barriers, cross-domain coordination and recovery; no duplicate domain state |

This mapping is a target ownership map, not proof that every listed source file has already moved. Some current modules mix multiple responsibilities. Split their behavior by operation and canonical data ownership; do not move a mixed class wholesale merely to match the table.

### Important split decisions

- `wf-origins` currently combines races, classes and professions. NPC owns the identity/species facts needed by every Character; RPG owns player class/progression rules. Shared definitions use one versioned catalog/API, not duplicated race or profession registries.
- `wf-relations` contains social facts and player-facing interactions. NPC owns canonical character-to-character relationship facts; RPG supplies player interaction flows. Politics may consume faction-level projections but cannot create a second social graph.
- `wf-reputation` must be classified by fact type: witnessed personal memory belongs with NPC/social facts; faction, realm and diplomatic standing belongs with Politics. No duplicate reputation ledger.
- `wf-war` contains strategic war and tactical battle concerns. Politics owns declarations, treaties, war goals and peace; RTS Battle owns battlefield execution and combat outcomes. One war/battle identity and event chain connects them.
- `wf-rts` currently includes settlement selection/commands. Its controls must be split into Battle and Settlements surfaces while reusing one command contract and the same underlying entities.
- `wf-army` contains economy/treaty concerns in addition to armies. Move each operation to its actual canonical owner only after ledger, save and caller audits.

## 4. One Character identity across all products

NPC is the sole canonical owner of persistent Character identity. There is one stable Character ID/UUID across work, soldier, commander, ruler, adventurer and player-facing roles. Role changes do not create Worker, Troop, Ruler or RPGCharacter replacements.

- Workers, soldiers, commanders, rulers, family members and quest givers are role/capability views over the same Character.
- Army, settlement, building, realm, squad, cohort and quest remain their own domain objects and reference Character IDs where appropriate.
- External Minecraft/mod entities can be adapted at three capability levels: observed entity, controllable unit, or fully integrated Character. Do not promise full Character semantics for an external entity until its data and lifecycle contract supports them.
- NPC creation must support data-driven templates/configuration and public adapter APIs; adding a custom NPC or adapting a compatible external mob must not require editing MAQAN source code.
- Death, migration, recruitment, inheritance, save/reload and entity materialization preserve identity, family, inventory, property and event history.

## 5. One command contract, multiple control surfaces

RTS selection, dialogue choices, hotkeys, first-person interaction and administrative tools are different input surfaces for the same domain command contracts.

For example, “assign this Character to build this blueprint” is one typed command with one validation, idempotency key, authority check, execution path and receipt. RTS UI, dialogue and hotkey dispatch that command; they must not implement separate construction behavior.

The same rule applies to move, attack, patrol, formation, recruit, work-zone assignment, harvesting, diplomacy and settlement management. Presentation may differ; canonical command semantics do not.

## 6. Optional composition and dependency rules

- Every product is a separate Forge mod artifact with declared dependencies and capabilities.
- WorldForge Core provides stable contracts and shared infrastructure. Product mods must not directly link against unrelated sibling implementation classes.
- NPC is a foundational dependency for full Character-driven Politics, Battle, Settlements and RPG interactions. A product may still expose non-character or direct local features when NPC is absent, but must not invent a parallel Character model.
- Simulation depends on Core only. Domain products register optional simulation participants when both sides are present; Simulation must not have mandatory compile-time dependencies on every domain product.
- RTS Battle can run with NPC without Politics or Settlements. RTS Settlements can run with NPC without Politics or Battle. Politics can run without RPG. RPG can use NPC interactions without requiring Politics, Battle or Settlements.
- The MAQAN ecosystem distribution may recommend or bundle all six products, but individual artifacts remain independently installable according to their declared capability requirements.

The precise required/optional dependency metadata is to be finalized from the source and runtime API audit. Do not weaken a required dependency until its integration tests prove the capability works without it.

## 7. Rust Compute and simulation ownership

Rust migration follows product/domain ownership, not current Gradle project boundaries.

- Simulation owns orchestration and shared bounded execution infrastructure, not all domain formulas.
- A domain operation is implemented once, in its canonical owner, and exposed to Simulation through an immutable/versioned request and typed result contract.
- Rust may own a pure calculation for an operation; Java/Minecraft retains canonical validation, domain mutation, persistence and physical side effects.
- The same production operation must never run in both Java and Rust. Parity runs are isolated tests only.
- One shared bounded runtime is used across domains until profiling and revision/atomicity contracts justify a different measured topology. No per-addon executors, duplicate schedulers or competing optimizers.
- Do not expand parallel execution while `WF-RT-0021` cross-domain apply atomicity or `WF-RT-0023` canonical writer/revision coverage is incomplete.
- Preserve full feature semantics and world scale. LOD, aggregation and batching change execution cost/representation, not the existence of people, actions, history, battles or consequences.

See `docs/architecture/RUST_COMPUTE_MIGRATION_AND_OPTIMIZATION_CONTRACT.md`, `COMPUTE_OWNERSHIP_REGISTRY.md` and `WORLDSIM_SCALE_AND_BATTLE_CONTRACT.md`.

## 8. Migration rules — no destructive mass move

1. Inventory every addon class, public API, command, event, datapack, registry, save file, network packet and test.
2. Assign every operation and canonical data type one owner; record consumers separately.
3. Identify mixed-responsibility classes and split by behavior, not by filename.
4. Introduce versioned Core contracts and compatibility adapters before removing direct sibling dependencies.
5. Add standalone product-combination tests and save migration tests before changing mod IDs or artifact packaging.
6. Move one coherent domain slice at a time; preserve current IDs/namespaces and old-save readability.
7. Remove old code only after parity, caller search, save/reload and campaign evidence prove the replacement is authoritative.
8. Update this document, the ownership registry and KNOWN_ISSUES in the same change as each ownership transfer.

## 9. Implementation stages

- **A — Architecture and source inventory:** map existing code/data/API and record mixed ownership. No mod ID or save namespace changes.
- **B — Core contracts:** Character reference, command contract, simulation participant lifecycle, capabilities, domain revisions and event/receipt contracts.
- **C — Simulation decoupling:** remove mandatory Simulation-to-domain implementation dependencies through optional adapters; preserve current campaign behavior.
- **D — Domain consolidation:** group internal packages/build artifacts into the six products without dropping features.
- **E — Composition matrix:** test supported standalone combinations and missing-mod behavior.
- **F — Full campaign and scale:** run multi-generation life, economy, politics, settlement construction, army recruitment and tactical battle; verify save/reload, determinism, queue progress and full scale.

No stage is complete from documentation or compilation alone. Runtime behavior and campaign evidence are required.


## Immutable definition publication and simulation boundaries

Datapack-backed definition sets are built in staging state and published as complete immutable generations. Consumers capture one generation per logical operation; they must not read several independently mutable maps during one calculation or UI projection. This rule applies to Origins race, class, profession, synergy and perk definitions and is the pattern for future definition registries. Canonical gameplay state remains owned by its domain module; immutable snapshots are read models, not duplicate authorities. Rust compute consumes versioned snapshots only and never owns datapack registries or Minecraft side effects.
