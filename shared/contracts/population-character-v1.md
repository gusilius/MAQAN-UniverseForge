# Unified Population / Character Contract v1

Status: implementation contract for WorldForge population integrations.

## Identity ownership

- `wf-people.CharacterRegistry` is the only authoritative registry of people.
- A person's stable identity is `Character.id` (currently generated as `c<number>` within a world).
- NPC progression uses the canonical owner key `n:<Character.id>`; player progression remains `p:<uuid>`.
- Minecraft NPC entities, settlement workers, troop members, army commanders, heroes, quest givers and dialogue participants must reference the same Character ID. They must not create parallel person IDs.
- A Minecraft entity is a body/projection. It may despawn, unload or be recreated without changing the Character.

## Separate concepts, shared identity

- Profession answers "what work can this person perform?" and is mutable.
- Population duties classify current assignments (worker, specialist, soldier, hero). Duties may overlap.
- WorldForge posts (realm/title offices) remain political appointments and are not professions.
- Skills and training history belong to the Character and survive profession changes.
- Needs are shared across civilian and military AI. They are deficits in the range 0..100 (0 = met, 100 = critical).
- Family relations, age, death and succession remain attached to the same Character record.

## AI boundary

- Population AI is deterministic and returns an intent, not a Minecraft side effect.
- Settlement, army, quest and NPC adapters validate resources/permissions and execute intents in their own domains.
- A completed action reports back to the shared population model to relieve needs.
- A failed or unavailable adapter must not fabricate food, resources, movement, combat outcomes or completed work.

## Persistence and compatibility

- Population fields are stored in Character `props`, which is already part of `wf-people/1` JSON persistence. Older saves without population keys remain valid.
- Character IDs and family links are never regenerated when profession, duty, skill, needs or AI intent changes.
- Dead characters remain in the registry; death releases posts but does not erase family links or history.
- No independent population count may be treated as a substitute for named people when the system requires individual identity.
