# Multi-dimension world runtime audit — baseline Run #2335

**Audit baseline:** `4a81f599e4a5e069999db7987045e88f3ce496e9` (Run #2335)  
**Working branch:** `feature/two-planet-runtime-from-2335`  
**Target runtime:** one Minecraft 1.20.1 / Forge 47.3.0 server process, one save, one installed mod set.

This audit distinguishes existing domain systems from proof that they are automatically bootstrapped in every dimension. A class existing or a unit test passing is not proof of live server integration.

## 1. Existing systems confirmed in source

- `wf-origins`: 46 unique race/subrace definitions in four datapack files (14 roots, 32 descendants), plus class, profession, race-trait/perk and synergy definitions. The registry is loaded and validated through `OriginsMod`.
- `wf-people`: canonical Character IDs, global CharacterAuthority, family links, race-aware naming, population simulation, Character persistence and cross-partition inventory/estate transaction recovery.
- `wf-realms`: government, terrain, level, doctrine and law definitions; autonomous government selection; Statecraft titles, succession, dynasties, diplomacy and war state.
- `wf-settlements`: physical Colony/Settlement records, map-blueprint import, resident links, worker simulation, construction and settlement economy.
- `wf-worldsim` + Rust: observed vanilla geography, map-derived geography, deterministic emergence bands/polities, population materialization, settlement creation, ruler/dynasty synchronization, strategic proposals and simulation LOD.
- `MapChunkGenerator`: real image-backed chunk generation from a .worldmap package.
- The image-map bootstrap deliberately strips imported realm sovereignty and keeps geographic province/region hierarchy and candidate settlement sites. Political identity is then generated from installed WorldForge data and runtime systems.

## 2. Two physical worlds already represented in one server configuration

The map-backed datapack adds `worldforge:<map-id>` to the same save that has `minecraft:overworld`. The mapped dimension uses `worldforge:image`; the Overworld retains the vanilla generator. This is the correct first two-world physical layout.

The existing workflow's two separate server launches are regression tests for each startup mode. They are not, by themselves, proof of simultaneous two-dimension gameplay. The image-map launch is the one that contains both the vanilla Overworld and the additional mapped dimension.

## 3. Defect fixed on the working branch

Before the fix, `VanillaWorldGeography` captured every loaded `ServerLevel`, including `MapChunkGenerator` levels. `WorldSimMod.snapshot()` also added `MapGeography.describe(map)` for the same map. This could feed one physical planet to Rust twice under two different map IDs and create duplicate emergence bands, population and settlements.

The working branch now:
- excludes `MapChunkGenerator` levels from vanilla chunk capture and pending-queue processing;
- excludes image-map dimensions from vanilla geography snapshots, region counters and political ownership;
- rejects vanilla political ownership transfers against image-map geography;
- leaves image-map terrain owned exclusively by `MapGeography`.

A first-chunk log marker (`MAQAN_MAP_CHUNK_GENERATED`) and a live dimension-resolution command were added so acceptance can distinguish a loaded datapack entry from actual MapChunkGenerator execution.

## 4. Dimension-local Character partitions added

Previously, `PeopleMod.registryFor(ServerLevel)` returned one `WORLD_REGISTRY` for every non-image dimension. That made Overworld, Nether, End and future vanilla-style dimensions share one local population partition even though geography was keyed by DimensionKey.

The working branch now:
- preserves the existing `minecraft:overworld` file and partition for save compatibility;
- creates a separate CharacterRegistry for every additional non-map DimensionKey;
- persists these partitions under `worldforge/wf_people_dimensions/<stable-dimension-token>.json`;
- registers each partition in the same global CharacterAuthority using a dimension-qualified partition key;
- includes all dimension partitions in save ordering, estate/inventory recovery, population fallback and the native population compute batch;
- resolves vanilla emergence sources to the CharacterRegistry for their own ServerLevel rather than reusing the Overworld registry.

This is a local-identity partition, not a second universe. Global Character UUIDs and cross-partition transactions remain under one CharacterAuthority.

**Migration caveat:** earlier builds stored Characters from all non-map dimensions in the legacy `wf_people_world.json` partition. The new code preserves that file as the Overworld legacy partition and creates empty per-dimension partitions for additional dimensions. A migration for any pre-existing non-Overworld Characters must use durable dimension/location evidence; it must not guess their destination. This is required before upgrading a save that already materialized NPC populations outside the Overworld.

## 5. Remaining integration blockers — do not call complete yet

### A. Political runtime IDs need explicit dimension/map scope

`RealmsMod.REALMS`, `RealmsMod.POLITICS` and `AUTONOMOUS_REALMS` are process-wide services. Some map title IDs are scoped by map name, but multiple authored maps can still contain equal local realm IDs. Several convenience lookups accept only a realm ID and can become ambiguous. Before enabling multiple authored political planets, every political actor, title, treasury, war, claim, dynasty and event reference must use a stable world-space-qualified ID.

### B. Map source identity is currently map-name scoped

`WorldForgeMod.loadedMaps()`, map Character registries, `EmergenceSource.authored(map)` and blueprint Colony IDs are keyed by map package name. Different map packages work as separate sources; loading the same package into two dimensions is not yet a supported independent-planet case. The next registry must key runtime planet instances by DimensionKey/PlanetId, not only by map package name.

### F. Strategic army/war/court services are still MapData-scoped

The audit of addon entry points found:
- `wf-army` keeps `ArmyEngine` instances in a `Map<MapData, ArmyEngine>` and resolves a realm to an engine by searching loaded map titles.
- `wf-war` keeps `WarEngine` instances in a `Map<MapData, WarEngine>`; its persistence and tick loops iterate loaded maps.
- `wf-court` keeps `CourtSim` instances per `MapData`.
- Vanilla emergent realms live in `RealmsMod.AUTONOMOUS_REALMS` / shared Statecraft, but they do not have a MapData-backed ArmyEngine/WarEngine/CourtSim owner. Therefore the vanilla planet can form a polity, but the full map-backed strategic army/war/court pipeline is not yet attached to it.
- `wf-troops` does carry physical DimensionKey and map scope in several identity/supply paths, but its strategic ArmyRef still needs a canonical world-space owner for non-map realms.

This is a major blocker for parity between the two planets. The solution is not to create a second army/war implementation: refactor the existing engines to use a shared WorldSpace/PoliticalProjection adapter, with map-backed and vanilla-dimension-backed projections.

### G. Trade is globally persisted but not yet truly inter-world

`wf-trade` has a process-wide `Trade` state and its visible shops are stored with a dimension key. However, capital/province caravan routing resolves endpoints through loaded `MapData` title graphs; when two realms are on different maps, it falls back to a direct line, and vanilla emergent realms have no map capital projection. Cross-dimension shipments and interplanetary transfer legs therefore need explicit route/transfer providers. A shared trade database alone is not proof of inter-world trade.

### H. Initial CK3-scale political bootstrap is not yet complete for every terrain provider

- `TerritorialIdentity.ensureDemographics` classifies existing realms; it does not create a complete realm hierarchy from bare province/region geography.
- `TerritorialIdentity.ensure` names/classifies existing realm/title records; it does not itself create the initial states.
- The map acceptance preparation strips authored realm sovereignty. The shared Rust emergence engine can create populations and polities over simulation time, but this is not equivalent to proving a complete initial CK3-scale political world is generated immediately on new-world creation.
- Vanilla geography is discovered incrementally from loaded chunks. This is appropriate for an infinite Overworld, but its strategic map is necessarily an observed/frontier model unless a bounded pre-generation or strategic sampling policy is configured.

The missing piece is one idempotent WorldGenesis bootstrap coordinator that consumes each dimension's terrain/geography adapter, produces initial population and political projections using the existing definitions, and then hands ongoing changes to WorldSim. It must not create campaigns or player objectives.

### I. Race assignment needs environmental/population context for emergent worlds

The 46 race/subrace definitions are present and loaded. Authored map realms receive origin-backed demographic mixes through `TerritorialIdentity.ensureDemographics`. Newly emergent populations call `Origins.assign` without a realm/population mix, so initial race choice is weighted from the global race catalog rather than the local planet/biome/culture ecology. The definitions exist; dimension-aware race distribution still needs to be connected.

### J. Cross-dimension travel is not yet proven

Global CharacterAuthority and transaction infrastructure exist, but no completed end-to-end acceptance currently proves a player/Character can move between the map planet and vanilla planet, restart mid-transfer, and recover without duplicate bodies, inventory, family or ownership records.

### K. Atlas still needs a canonical multi-planet registry contract

Atlas and WorldForge must consume the same stable PlanetId ↔ DimensionKey ↔ terrain-provider mapping. A second independently maintained planet list is not acceptable.

## 6. Acceptance sequence

1. Build every addon after the geography de-duplication and dimension-local Character partition changes.
2. Start one server containing vanilla Overworld + map dimension; force a mapped chunk and require the first-chunk generator marker.
3. Prove the two dimensions produce separate geography source IDs, Character partitions, settlement coordinates and polity IDs.
4. Add a third non-map test dimension and prove its Character/geography partition is independent while sharing WorldId, clock and CharacterAuthority.
5. Qualify political IDs and all cross-domain lookups by stable PlanetId/DimensionKey before multiple authored map planets are accepted.
6. Validate local race/ecology distribution using the existing 46 definitions, then prove save/restart stability.
7. Exercise cross-dimension Character/player transfer and crash recovery.
8. Publish the canonical planet registry to Atlas and MAQAN UI.

## 7. Non-goals

- Separate Minecraft server processes per planet.
- Separate mod installations or independent global Character authorities.
- A campaign, scenario, scripted objective or forced player progression.
- Replacing the vanilla Overworld with the map generator.
- Duplicating the existing race, population, court, government, settlement or simulation systems.
