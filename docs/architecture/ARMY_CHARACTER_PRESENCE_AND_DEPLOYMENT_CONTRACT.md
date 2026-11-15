# Army, Character Presence and Deployment Contract

**Status:** mandatory architecture contract; runtime conformance is partial and must not be assumed.
**Scope:** wf-army, wf-troops, wf-people, wf-settlements, WorldSim and their Minecraft adapters.
**Canonical identity:** `wf-people.CharacterRegistry` / `Character.globalId`.
**Related contracts:** `docs/architecture/WORLDSIM_SCALE_AND_BATTLE_CONTRACT.md`, `docs/architecture/COMPUTE_OWNERSHIP_REGISTRY.md`.

## 1. Core rule

An army is a strategic military organization. A Character is a person. A Minecraft entity is a physical body/projection of a Character. These are related, but they are not interchangeable records and must never be collapsed into one lifecycle.

**Folding an army, ending a camp, unloading a chunk, leaving an area, switching camera mode, or running an optimization must never delete a living Character or erase their civilian life.**

The system must distinguish three independent axes:

1. **Military membership/duty:** regular soldier, reserve, militia, garrison, levy, commander, support, etc.
2. **Civilian and social life:** residence, household, family, employment, profession, needs, property, relationships and local duties.
3. **Physical/world representation:** a loaded Minecraft body, a saved-but-unloaded body, or a non-entity strategic/cohort representation.

Membership in an army does not imply that a Character must be physically standing in a formation. Nor does the absence of a deployed formation imply that the Character or their civilian body should be removed.

## 2. Canonical identity and ownership

- `Character.globalId` is the stable world-wide identity. Worker, Troop, Militia, Garrison, Commander and other role records are projections/references to this identity, not replacement people.
- Do not create a second Character when a resident joins a militia, is mobilized, changes unit, becomes a commander, is wounded, returns home, or is demobilized.
- Do not create a second Worker/Troop identity for the same person. A role change changes assignments/duties; it does not replace the person.
- Preserve the existing Character's UUID, family links, household/residence, profession, skills, inventory/equipment, wounds, traits, opinions, social memory, property and lifecycle history unless an explicit in-world action changes them.
- CharacterRegistry remains the identity authority. Army and settlement systems may keep bounded indexes/receipts, but they must resolve to canonical Character IDs and must not become parallel person registries.
- For named/individualized soldiers, canonical Character identity is authoritative. Aggregate headcounts are derived/indexed projections and must reconcile against membership changes.
- Anonymous mass cohorts are allowed only as compressed military simulation state. They are not extra named people. When a cohort member becomes individually significant or physically materialized, assign a deterministic stable identity exactly once and persist the binding. Never materialize a second Character for an existing member.
- Every roster transition is idempotent and durable. Use operation IDs/receipts so crash recovery cannot add the same person twice or lose them between civilian and military projections.

## 3. Civilian residents and militia

Militia and reserve membership is an additional military relationship, not a reason to erase civilian residency.

- A village resident who is a militia member remains a resident when the army marches elsewhere.
- A resident may retain a civilian profession, household, family and settlement membership while also having a militia/reserve duty.
- Mobilization may temporarily suspend or reprioritize work, but must preserve the employment/profession and the exact return assignment. Demobilization restores the prior civilian state; it does not create a replacement Worker or Character.
- A militia unit stationed in its home settlement remains represented there when the field army is folded or moved. Only an explicit muster, movement, casualty, transfer, desertion, retirement or other domain event changes its state.
- Garrison, watch, patrol and local-defense assignments are not deleted merely because the player is far away or the strategic army is not deployed.
- If a person is killed, the normal Character death, inheritance, household and property rules apply. A dead soldier is not silently returned to the roster, and a living soldier is not silently deleted from it.

**Important implementation consequence:** the current Worker-to-Soldier test demonstrates a same-Character handoff, but its worker-duty removal is not sufficient for the desired militia model. The target model must support concurrent civilian residence/employment and military reserve membership, with an explicit mobilized/away-from-work state rather than destructive role replacement.

## 4. Army presence states (organization-level)

These are states of the Army's field organization, not lifecycle states of its members.

### A. Strategic march / reserve organization

- The political/strategic map displays one army token and its canonical strength, composition, commander, supply, morale, speed, route and orders.
- No crowd of field-soldier entities is required in the Minecraft world solely to represent this strategic token.
- The Army remains a durable domain object with its roster, orders and receipts.
- Residents/militia who are not mobilized remain in their settlement life. A home garrison remains at its post.
- Named soldiers who are already physically resident in the world are not discarded merely because the strategic army token is being used.

### B. Camp deployment

- A field army may materialize a camp: tents, fires, wagons, guards, commanders and deployed personnel.
- Only personnel assigned to this camp are projected there. A militia resident who remains on home duty is not copied into the camp.
- Camp entities are temporary world projections or camp fixtures. Folding the camp removes those temporary fixtures and camp-only projections, not Characters, households, equipment, inventory, wounds, family or army membership.
- If a deployed soldier is an existing Character, the same Character is represented at the camp. No clone is created.
- Rest, healing, resupply, recruitment, desertion, transfer and other camp actions commit through the existing canonical domain owners and durable receipts.

### C. Battle deployment

- A battle materializes the required combatants and tactical formations for the encounter.
- RTS and first-person control are two control surfaces over the same battle and same Character IDs; they must not create separate tactical populations.
- Tactical casualties, injuries, morale, equipment loss, capture, retreat and death reconcile exactly once into canonical Character and Army state.
- At battle completion, survivors transition to camp, strategic march, garrison or home/reserve according to explicit orders and duty. No automatic deletion or identity replacement is allowed.

Camp and battle deployment are mutually exclusive deployment modes for one field organization unless a future contract explicitly models detached sub-forces. They are not separate armies.

## 5. Physical body lifecycle and settlement persistence

A physical Minecraft body is a replaceable representation, but the desired resident NPC must not vanish as a consequence of army folding or distance-based cleanup.

- Do not call `discard()` on a living resident body merely because no player is within a retention radius, the army is not deployed, or an LOD budget was reached.
- Resident and garrison bodies must have a durable identity binding to the canonical Character and a deterministic recovery path after save/restart/chunk unload.
- When Minecraft unloads a chunk, the entity may naturally stop ticking and be serialized with that chunk. This is not Character removal. On chunk reload, reconcile the saved entity with the canonical Character binding; restore the same logical body, not a duplicate.
- A loaded settlement must not contain duplicate active bodies for one Character/body-kind. Reconciliation must handle stale UUID bindings, removed chunks, transfer between dimensions and interrupted spawn/save operations.
- If distant simulation needs to reduce entity ticking, use an explicit persisted hibernation/LOD state with enough data to restore the same Character body and gameplay state. Never implement LOD by deleting the canonical person or silently clearing their military/civilian assignment.
- Field-camp fixtures and purely tactical visual proxies may be removed on fold, but persistent residents, home militia and garrison NPCs are outside that cleanup scope.
- Body binding belongs to the canonical Character entity-binding map (or a formally versioned replacement owned by CharacterRegistry), not only to a process-local map such as `Bodies.LIVE`.
- Chunk unload/reload, server restart, player disconnect/reconnect and dimension transfer must be idempotent and must not duplicate Characters, bodies, roster counts or inventory.

Minecraft cannot tick an entity in an unloaded chunk. The contract therefore requires durable save/reconciliation and continuity of identity/state, not impossible off-screen ticking of every resident. The resident remains part of the living settlement simulation even while its chunk is unloaded.

## 6. State transitions

All transitions are server-authoritative, validated and receipt-backed.

| Transition | Allowed effects | Forbidden effects |
|---|---|---|
| Reserve/militia -> mobilized | Set mobilization order; suspend work temporarily; route same Character to muster point | Delete residence, family, Worker identity or Character |
| Mobilized -> camp | Bind same Character to camp body/projection; update location/order | Clone Character or count same soldier twice |
| Camp -> battle | Reuse same Character identity; attach tactical state/formation | Create a second troop-person record |
| Battle -> camp/march | Apply casualties and outcomes once; restore survivor assignment | Discard living Characters or lose equipment/wounds |
| Army folded -> home/reserve | Return eligible residents/garrisons to their canonical posts; remove camp-only fixtures | Remove militia membership or settlement NPCs |
| Chunk unload/reload | Save/unload entity; reconcile on load | Treat unloaded entity as dead or spawn a duplicate |
| Server restart/recovery | Replay/reconcile durable receipts and bindings | Re-enlist, re-recruit, or re-materialize the same member twice |

A failed transition must leave the prior committed state recoverable. Do not update one projection irreversibly before the matching canonical receipt/state transition is durable.

## 7. Compute and execution ownership

- Java/Forge owns CharacterRegistry and Army/Settlement domain validation, Minecraft Entity spawn/removal, world/block interaction, chunk lifecycle, entity binding, persistence and application of outcomes.
- Rust owns deterministic pure calculations over immutable snapshots: strategic movement, supply, mass battle resolution, morale/tactical evaluation and large route calculations.
- Rust must not spawn/despawn Minecraft entities, mutate Character/Army/Settlement registries, or decide that a resident should be deleted for performance.
- Do not add a second scheduler, executor, Character registry, Army registry, event journal or optimization layer. Extend existing canonical owners and shared runtime only where profiling justifies it.
- The native army combat batch remains a compute-only operation. Its results are applied to the existing Java-owned Army/Character state and do not define physical deployment policy.

## 8. Source audit and known gaps (2026-11-16)

Observed in the current branch:

- `Character` documents that a body may disappear while the Character remains in `CharacterRegistry`; `globalId` is stable.
- `CharacterRegistry.bindEntity/unbindEntity` provides a canonical per-Character body-binding mechanism and prevents reusing one entity UUID for multiple Characters.
- `Army` currently stores aggregate unit counts by type; it does not yet expose a canonical per-member Character roster in the inspected source.
- `CharacterMilitaryIdentityTest` covers Worker -> Soldier -> Worker using one Character, but it removes the Worker duty during enlistment. It does not yet cover concurrent resident + militia membership, mobilization leave/return, or physical body persistence.
- Settlement `Bodies` currently uses process-local `LIVE`, discards bodies when the player-retention radius is left, and `WFWorker.shouldBeSaved()` returns false. This does not satisfy the resident-body persistence requirement.
- The current physical worker projection is therefore LOD/near-player scoped, not a durable resident-body lifecycle. Do not claim this contract is implemented until the body binding, save/reload reconciliation and duplicate prevention are completed.
- Army/troop/camp/battle deployment source paths require a further end-to-end audit before runtime changes. This document is the target contract, not evidence that those transitions already exist.

## 9. Required acceptance tests

1. Resident militia member remains the same Character and remains resident while the field army marches/folds.
2. Mobilization temporarily suspends work and demobilization restores the exact prior employer/profession/assignment.
3. Worker + militia/reserve concurrent duties survive save/load without duplicate role/person records.
4. Camp deployment and folding preserve all Character UUIDs, family links, inventory/equipment, wounds, skills, morale and property.
5. Battle deployment uses the same IDs as camp/strategic roster; casualties and returns apply exactly once.
6. A resident/garrison entity survives player departure, chunk unload/reload and server restart with no duplicate body.
7. Crash at each point of spawn, bind, roster receipt and save is reconciled idempotently.
8. A stale body UUID or manually removed entity is repaired without deleting the Character or duplicating the Army roster.
9. Large population LOD reduces compute/tick work without deleting residents, militia, family or canonical history.
10. Windows Forge dependency-closure build, Java logic regressions, native parity tests and an ordinary-world campaign all pass. CI compilation alone is not gameplay acceptance.

## 10. Implementation sequence

1. **Contract (this change):** define the independent identity, civilian-life, military-membership and body-presence axes.
2. **Character/militia model:** add durable military membership and mobilization/leave state while preserving resident/employment data.
3. **Body persistence:** replace process-local-only settlement body tracking with canonical Character bindings, saved entity state and chunk/restart reconciliation; prevent duplicate active bodies.
4. **Army roster bridge:** reconcile aggregate army counts with named Character members and anonymous cohorts using durable idempotent transitions.
5. **Deployment coordinator:** add camp/battle deployment state and server-thread projection adapters; keep RTS and first-person as shared controls.
6. **Rust compute integration:** only after the Java snapshots and canonical apply boundaries are complete, extend Rust inputs/results for the required strategic/tactical calculations.
7. **Acceptance:** run focused regressions first, then targeted addon dependency closure, native Windows/Linux checks and a full ordinary-world campaign. Do not run the entire repository build after every small change.
