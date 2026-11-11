# MAQAN Autonomous World Simulation Engine

Status: active implementation on `feature/autonomous-world-simulation-engine`.

## Purpose

`wf_worldsim` is a new, separate Forge mod that coordinates autonomous world creation and simulation across the existing WorldForge addons. It is not a replacement for RTS, court, war, army, settlements, trade, people, realms, Genesis, or Atlas. Those addons remain authoritative domain executors.

The Rust library `worldforge-worldsim` is a deterministic decision and macro-world generation engine. Java/Forge owns Minecraft state, Character identity, persistence, permissions, entity creation, block placement, and execution of accepted proposals.

## Non-negotiable invariants

1. One persistent world; no disconnected game modes or duplicate simulation worlds.
2. Existing addon systems remain the owners of their domain data.
3. Rust receives immutable snapshots and returns proposals; it never writes Minecraft state.
4. Every proposal has a stable request ID and an explanation. Host-side execution must validate current state and use idempotent domain APIs where available.
5. No scripted wars, marriages, heirs, deaths, winners, construction completion, or territorial outcomes in autonomous campaigns.
6. A fixed random seed guarantees reproducibility, not a fixed story. Decisions depend on evolving state and seeded probability.
7. Simulation duration is event-driven. A generational campaign ends only after the requested number of actual lineage-generation transitions, not after an arbitrary day count.
8. World generation is a one-time creation pipeline. Opening Atlas or changing map overlays must never regenerate the world.
9. Existing worlds are never silently replaced. Generation requires an explicit new-world operation and durable provenance.
10. Failure to load the native library must not corrupt or partially apply a world. Java must report native unavailability and preserve authoritative state.

## Runtime architecture

```text
Minecraft / Forge world
  ├─ wf_people       CharacterRegistry, identity, lifecycle
  ├─ wf_court        marriage, succession, factions, court events
  ├─ wf_realms       governments, laws, doctrines, treasury
  ├─ wf_war          casus belli, war lifecycle, peace, occupation
  ├─ wf_army         recruitment, strategic movement, campaigns
  ├─ wf_troops       physical unit binding and battlefield entities
  ├─ wf_settle       workers, buildings, production, needs, logistics
  ├─ wf_trade        markets, caravans, tariffs, embargoes
  ├─ wf_rts          selection, orders, formations, command layer
  ├─ Genesis         world/settlement provisioning
  └─ Atlas           shared geography and political/map projections
            │ immutable snapshot
            ▼
       wf_worldsim (Java coordinator)
            │ versioned UTF-8 JSON over JNI
            ▼
   worldforge-worldsim (Rust)
      ├─ macro-world generation
      ├─ political/strategic decision proposals
      ├─ settlement and workforce planning
      ├─ dynastic matching
      └─ deterministic diagnostics
            │ proposal frame
            ▼
 Java validation → domain APIs → durable persistence → event journal → Atlas refresh
```

## World creation pipeline

The complete target pipeline is:

1. Source selection: procedural seed or user-supplied map image.
2. Raster analysis: water, elevation, climate, vegetation, coast, rivers, passes and resource potential.
3. Geography: connected landmasses, mountain chains, river basins, climate/biome fields and navigable regions.
4. Political geography: provinces, adjacency, defensible borders, realm seeds, capitals, duchies/counties/baronies.
5. Society: cultures, races, languages, population distribution, professions, families and founder houses.
6. Settlement sites: food, water, wood, stone, ore, transport and defensibility scoring.
7. Initial simulation state: population and institutions are seeded, but future outcomes are not.
8. Minecraft materialization: terrain, map data, titles, settlement anchors and Atlas source data are created once and persisted.
9. Autonomous simulation starts from the resulting world snapshot.

The current Rust generator is the first macro-geography slice: deterministic realm/province/capital seeds, simple elevation/moisture/temperature/biome fields, resource potential and settlement candidates. It does not yet analyze arbitrary source images or materialize Minecraft chunks. Do not describe it as a finished map generator.

## Autonomous simulation domains

Each domain is evaluated from current state and produces proposals, not pre-authored story events:

- Population: births, natural mortality, migration, ageing, family growth, needs, education and profession changes.
- Dynasties: marriages, inheritance, succession, claims, house branches, regencies and legitimacy.
- Politics: councils, factions, loyalty, autonomy, rebellion, realm formation, unification and fragmentation.
- Diplomacy: opinion, treaties, marriage ties, rivalries, claims, guarantees and truces.
- War: declaration decisions, objectives, mobilization, supply, campaigns, sieges, tactical battles, peace and aftermath.
- RTS: strategic orders, physical unit materialization, formation, command groups, morale and battlefield results.
- Settlements: founding, site choice, zoning, buildings, jobs, roads, production, housing, food, defence and growth.
- Economy: production chains, shortages, prices, markets, trade routes, tariffs, debt, treasury and logistics.
- Professions: worker specialization, apprenticeship, skill progression, job mobility and labour shortages.
- World events: disasters, disease, weather, discoveries, migration, crime, faith and cultural change.
- Atlas: geography, political borders, military fronts, trade, population, culture, terrain, exploration and history layers.

## Decision cadence and performance

The host owns the canonical clock. Rust receives the authoritative day/tick and computes only the due domains. The target cadence is:
- daily: needs, local production, movement and short-range logistics;
- monthly: markets, diplomacy, local politics and strategic assessments;
- yearly: court life cycle, births/deaths, marriage matching, succession, institutions and long-term planning;
- event-driven: war declarations, battles, sieges, disasters, migration waves and realm formation.

Large worlds should use deterministic work queues, dirty-domain tracking, bounded decision budgets and stable snapshots. Do not iterate every person for every subsystem every Minecraft tick. Fast-forward must preserve the same event ordering and bounded-step semantics as normal simulation.

## Generation-based campaign acceptance

A valid long campaign must:
- begin with founder characters and a small set of initial social/economic conditions;
- contain no preselected wars, winners, marriages, heirs, deaths or building completion;
- create all subsequent people, families, professions, settlements, realms and wars from autonomous domain transitions;
- preserve global Character IDs through worker/soldier/ruler role changes;
- record every applied and rejected proposal with day, cause, actors, source subsystem and resulting state;
- persist/reload at checkpoints without duplicate events or identity changes;
- track actual ancestry depth and realm institutional continuity;
- continue through ten actual post-stabilization generations after stable state formations emerge;
- stop only when the requested generational criterion is met, or report a genuine deadlock with evidence.

A long wall-clock or day-count run alone is not a generational acceptance test.

## Current implementation boundary

Implemented in this branch:
- new Forge mod directory `wf-worldsim`;
- separate pure-Rust crate `worldforge-worldsim`;
- deterministic proposal engine for border-war scoring, settlement construction priorities, workforce proposal shape, recruitment proposal shape and cross-realm marriage matching;
- deterministic macro-world generator for realm/province/capital seeds, basic geography and settlement candidates;
- JNI JSON boundary for decision and generation requests;
- Java snapshot coordinator for loaded realms, borders, settlements and living court characters;
- application paths for validated settlement building placement, marriage proposals and war declarations;
- persistent event journal and settlement project cooldowns;
- in-game status/history commands;
- Windows CI build and native DLL packaging.

Not yet complete:
- generated map image segmentation and source-image calibration;
- writing generated provinces/titles/biomes/height fields into the existing MapData/MapChunkGenerator pipeline;
- founder population and settlement provisioning through Genesis;
- full workforce snapshot and safe profession executor;
- recruitment/summoning executor through settlement recruitment sites and ArmyEngine;
- autonomous political state formation/unification/fragmentation;
- military supply/AI decision closure, physical battle-result integration;
- full trade/economic snapshot and decision executor;
- event-sourced replay, cross-addon atomic checkpoints and ten-generation campaign harness;
- complete Atlas live-profile refresh from the same world event stream.

This boundary is intentional: a built DLL and successful unit tests do not prove a living autonomous Minecraft world. The feature is not complete until these integration and long-campaign acceptance gates pass.
