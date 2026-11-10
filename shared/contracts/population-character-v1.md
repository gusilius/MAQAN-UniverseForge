# Unified Population / Character Contract v1

Status: implementation contract for WorldForge population integrations.

## Identity ownership

- `wf-people.CharacterRegistry` is the only authoritative registry of people.
- A person's canonical stable identity is the immutable world-level `Character.globalId` UUID. The map-local `Character.id` (`c<number>`) remains a compatibility/storage-partition key only.
- NPC progression uses the canonical owner key `n:<Character.globalId>` so moving a person between maps or settlements cannot create a second RPG profile. Old scoped keys `n:<population-scope-id>:<Character.id>` and unambiguous legacy `n:<Character.id>` keys are migrated; ambiguous legacy IDs fail closed. Player progression remains `p:<uuid>`.
- Minecraft NPC entities, settlement workers, physical troop members, army commanders, heroes, quest givers and dialogue participants must reference the same Character ID. They must not create parallel person IDs.
- Settlement `Worker.person` and physical `TroopEntity.characterId` are durable references to `Character.globalId`; map-local `cN` references are accepted only by one-time legacy resolvers in their original partition. Soldier combat XP/rank is mirrored into the same Character combat skill record.
- A Minecraft entity is a body/projection. It may despawn, unload or be recreated without changing the Character.

## Separate concepts, shared identity

- Profession answers "what work can this person perform?" and is mutable.
- Population duties classify current assignments (worker, specialist, soldier, hero). Duties may overlap.
- WorldForge posts (realm/title offices) remain political appointments and are not professions.
- Profession definitions are data-driven (`wf_professions`); changes retain profession history.
- Skills and training history belong to the Character and survive profession changes. The optional `wf-progress` bridge mirrors RPG ranks through the global Character UUID owner key.
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


## Lifecycle hardening — Phase VII

- Lifecycle changes are explicit events: birth, family-linked birth, marriage, calendar-derived life-stage transitions, post appointment/dismissal, profession changes, hire, fire, migration, duty assignment/removal, death and registry restoration.
- Hiring, firing and migration operate on an existing Character ID. They never allocate a replacement identity.
- Settlement worker departures are persisted as pending lifecycle records before the Character registry acknowledges them. Starvation/raid deaths become Character deaths; low-approval departures become migration events.
- A non-empty Worker.person or TroopEntity.characterId that cannot be resolved is a referential-integrity fault. Adapters must log/retain the unresolved reference and must not silently create a replacement person.
- A dead Character is never revived or replaced by entity synchronization. The physical body is removed or held for repair.
- Minecraft entity UUID bindings are stored under population.entity_bindings by body kind. One entity UUID cannot be owned by two different Characters; multiple body kinds may reference the same Character.
- Registry restoration validates duplicate IDs and entity UUID ownership before replacing live state. Registry writes now use wf-people/2; wf-people/1 saves are migrated in memory without changing Character IDs, family links or existing props. Corrupt registry files fail closed instead of regenerating court people over unreadable data.
- Entity unload is not death. Persistent body references are refreshed on entity join; death explicitly removes its binding.
