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

### C. Race assignment needs environmental/population context for emergent worlds

The 46 race/subrace definitions are present and loaded. Authored map realms receive origin-backed demographic mixes through `TerritorialIdentity.ensureDemographics`. Newly emergent populations call `Origins.assign` without a realm/population mix, so initial race choice is weighted from the global race catalog rather than the local planet/biome/culture ecology. The definitions exist; dimension-aware race distribution still needs to be connected.

### D. Same Genesis lifecycle must be explicitly coordinated per planet

The map-backed path has a map identity/court bootstrap and settlement blueprint import. Vanilla dimensions are observed incrementally as chunks load and then enter the shared emergence engine. These are different terrain adapters, which is correct, but acceptance must prove that each produces its own geography, population, settlement and polity projections with no cross-dimension leakage. Do not create a second political-generation implementation.

### E. Cross-dimension travel is not yet proven

Global CharacterAuthority and transaction infrastructure exist, but no completed end-to-end acceptance currently proves a player/Character can move between the map planet and vanilla planet, restart mid-transfer, and recover without duplicate bodies, inventory, family or ownership records.

### F. Atlas still needs a canonical multi-planet registry contract

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
