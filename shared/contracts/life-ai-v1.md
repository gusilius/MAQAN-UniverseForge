# WorldForge Life AI v1

## Identity and ownership
- A Character is the only persistent person identity.
- AI state, needs, cooldowns, last action, outcome and reason are stored in Character.props; they are serialized with the Character registry.
- Settlement workers and Minecraft NPC entities are projections/adapters. They do not own a second needs or identity record.
- Resource consumption and settlement work remain authoritative in wf-settlements; AI cannot award need relief when its executor returns BLOCKED or RETRY.

## Decision pipeline
1. Needs: hunger, rest, safety, social, purpose and leisure are shared deficits from 0 (satisfied) to 100 (critical).
2. Schedule-aware utility: meal windows, work hours, training time, evening social time and night rest bias goal scores.
3. Behavior-tree emergency selector: critical hunger, threat and exhaustion are checked before ordinary goals.
4. Bounded GOAP: one highest-deficit goal is selected; only operators whose environment preconditions are true enter the plan.
5. Execution adapter: the owning subsystem performs the action and returns SUCCESS, BLOCKED or RETRY.
6. Effects: needs and skills change only after SUCCESS.

## Current actions
| Action | Authoritative adapter effect |
|---|---|
| Eat | Withdraw one food unit from settlement stock |
| Sleep | Set worker sleeping activity state; need relief follows successful completion |
| Seek safety | Respond to active settlement raid / threat state |
| Socialize | Requires another worker in the same settlement |
| Work | Requires an assigned production/construction job; production remains in Settlement.tick |
| Train | Adds XP to the Character's selected training skill |
| Recreate | Requires a completed leisure facility |

## LOD cadence
- ACTIVE: one decision per world-minute.
- SETTLEMENT: one decision per five world-minutes.
- REGIONAL: one decision per hour.
- REMOTE: one decision per six world-hours.
- LOD is derived from nearest same-dimension player distance. Remote settlement economy, food consumption, production, construction, trade/logistics and settlement data simulation continue without a player or loaded settlement chunk.

## Known limitations
- The first runtime adapter covers Character-backed settlement workers. WFNpc courtiers, travelling populations, armies and independent wildlife still need domain-specific action executors.
- Sleep/safety/social/recreation currently update activity state and shared needs; full navigation to beds, shelters, social venues, dialogue and animation remains future work.
- Natural mortality and family expansion are not synthesized by this AI kernel. Settlement migration/death events are reconciled through Character lifecycle adapters. Demographic policies depend on Stage VII world-scoped registry consolidation.
- Pure Java tests verify the decision kernel. Forge CI verifies compilation; unattended in-game runtime and chunk lifecycle tests remain required.