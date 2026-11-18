# Multi-Planet World Runtime

**Status:** implementation contract / staged target architecture. This document does not claim that multi-dimension runtime or in-game world generation is already implemented.

## 1. Product decision

MAQAN runs as **one Minecraft dedicated/integrated server process with one installed mod set and one save**. Multiple planets are represented by dimensions (Minecraft levels), not by separate servers, modpacks, campaigns, or independent game instances.

Initial target: two playable planet dimensions, with a third reserved as an optional space/transit layer only when the existing Atlas/Cosmic Horizons runtime can support it without duplicating world ownership.

- **Mapped planet:** the existing selected map/terrain source is used as the terrain provider for one dimension. Preserve its scale, coordinates, landmarks, and intended geography; do not silently replace it with vanilla terrain.
- **Vanilla planet:** a second dimension uses normal Minecraft Overworld terrain generation and its normal world-generation rules.
- **Optional third dimension:** space/orbital transit layer, not required for the first acceptance milestone. It must not be introduced merely as an empty placeholder.

The two initial dimensions are peers in one universe. Neither is a campaign, scenario, quest chain, or progression gate. A new world is a sandbox simulation with emergent inhabitants and political structures.

## 2. One server, shared universe

Keep one server process, one world save root, one mod installation, one authoritative universe identity, and one global simulation clock.

Shared universe services:
- WorldId / universe identity and Genesis lifecycle;
- canonical CharacterAuthority and global Character UUIDs;
- global family, lineage, ownership, inventory, treasury, diplomacy, reputation and event identities;
- world calendar and simulation scheduling;
- global Atlas catalogue and planet-to-dimension registry;
- cross-dimension travel/transfer coordinator and durable receipts.

Dimension-local state:
- Minecraft ServerLevel, chunks, block entities, local weather/time presentation where supported, terrain and local spawn/placement;
- local settlement/army physical projections and local pathfinding;
- dimension-local map coordinates and spatial indexes.

A dimension is not a second identity domain. A Character moving between planets retains the same UUID, family, skills, equipment, inventory ownership, relationships and political affiliations. Physical entities are transferred/recreated only through a crash-safe transfer transaction; never create a second canonical Character.

## 3. Planet and dimension registry

Introduce or extend one authoritative registry with stable identifiers:
- PlanetId — stable universe-level identity;
- DimensionKey — Minecraft resource key for the physical level;
- TerrainProviderId — mapped terrain, vanilla generator, or a future provider;
- PlanetDescriptor — name, classification, parent system, environment metadata and presentation data;
- PlanetRuntimeState — lifecycle, generation status, readiness and last durable snapshot/version.

Required invariants:
1. A PlanetId maps to at most one active DimensionKey in a save.
2. A DimensionKey cannot be silently rebound to another planet after world creation.
3. Unknown or unavailable optional planet providers fail closed with a clear diagnostic; they must not corrupt or regenerate an existing dimension.
4. The registry is versioned and migratable; dimension identity is never inferred from display names.
5. Atlas displays the same registry and stable IDs; it must not maintain a duplicate planet catalogue.

## 4. Same Genesis pipeline, different terrain providers

World creation must run one shared Genesis pipeline regardless of terrain source:

1. Create/validate universe identity and global registries.
2. Register the configured planet dimensions and their terrain providers.
3. Generate or import terrain for each dimension.
4. Scan terrain into a common geographic representation.
5. Derive geography: landmasses, coastlines, water, elevation, climate, resources and traversability.
6. Generate settlements and settlement names.
7. Generate population, races/ancestries, cultures, languages and personal names from installed data providers.
8. Generate political organizations using supported formation types and naming rules; assign territories through geography and settlement/population state.
9. Build the CK3-scale strategic map: provinces → regions → realms/states → higher formations, with borders and overlays.
10. Publish validated snapshots to Atlas and runtime consumers.
11. Mark Genesis complete only after all required stages have durable success receipts.

Only terrain generation/import differs by provider. The mapped planet and vanilla planet must use the same downstream geography, population, settlement and political-generation pipeline. Do not create a special reduced simulation for the vanilla dimension.

No fixed campaign, mandatory ruler, prewritten objective, scripted war, or player-start condition is introduced. Initial political structures are generated from data, geography and simulation rules and remain emergent.

## 5. Geography and strategic map are not block-coordinate aliases

The CK3-scale map is a world-level geographic model, not a direct copy of Minecraft chunks or a separate campaign map.

Every geographic feature/province must carry:
- stable ID and owning PlanetId;
- normalized/world-map geometry and optional dimension-local spatial bounds;
- terrain/climate/resource summaries derived from the actual terrain provider;
- settlement references and traversability/cost metadata;
- ownership/border history and version.

A province must never span two planet dimensions. Cross-planet political/economic relationships are represented by universe-level links, not by pretending the planets share contiguous block coordinates.

## 6. Dimension travel and continuity

Cross-planet travel is a first-class domain operation:
- validate destination planet and arrival location;
- reserve/commit a durable transfer receipt;
- preserve canonical Character identity and all Character-owned state;
- transfer the physical player/entity only after the domain transfer is recoverable;
- recover or roll back interrupted transfers after restart;
- prevent duplicate live entities and duplicate inventory/ownership effects.

Travel methods (spacecraft, portals, gates, future warp systems) are providers of the same transfer contract. Do not use arbitrary teleport commands as the final gameplay implementation. The initial milestone may expose an administrative/test transfer path for validation, but it must use the same transaction coordinator.

## 7. Mod compatibility and optional integrations

Keep current module boundaries:
- WorldForge owns world identity, Genesis, geography, population, settlement and political simulation.
- Atlas owns cosmic catalogue, planet/system visualization, navigation and spatial workspace.
- MAQAN Platform owns the common UI shell and workspace lifecycle.
- Cosmic Horizons or a map/terrain mod is an optional provider/adapter, not a hard dependency of the shared Genesis pipeline unless the existing runtime explicitly requires it.
- Shared contracts carry versioned IDs/DTOs/events only; they do not own domain state.

Do not merge Forge mod IDs, Forge classpaths, or project build versions as part of this feature. Do not introduce a mandatory Atlas → WorldForge or WorldForge → Atlas dependency. Missing optional integrations must degrade with diagnostics rather than prevent a vanilla-planet server from starting.

## 8. Persistence, restart and compatibility

Persist:
- universe identity and schema version;
- planet/dimension registry and immutable mappings;
- per-dimension terrain-provider identity and generation seed/config fingerprint;
- Genesis stage receipts and generation version;
- global Character/family/economy/political registries;
- dimension-local physical projection indexes;
- cross-dimension transfer journal/outbox.

On restart, reconcile the registry against dimensions actually present in the save. Never silently recreate a missing dimension over existing data. A failed or incomplete Genesis stage resumes idempotently from its durable receipt; it must not mark the world complete before all required writes have committed.

Existing single-dimension saves must migrate as one existing planet without resetting terrain or Character IDs. New dimensions are additive and opt-in through a migration/configuration path; do not surprise users by generating a second planet inside an existing save.

## 9. Configuration

Expose through the unified MAQAN Settings UI:
- enabled planet slots (2 initially; third optional);
- planet/dimension IDs and display names;
- terrain provider per planet;
- map source and import/scale options for the mapped planet;
- vanilla generator settings for the vanilla planet;
- per-planet chunk generation and simulation distance;
- NPC/army simulation LOD and budgets per dimension;
- cross-dimension travel availability and debug controls.

Configuration must be validated before world creation. Changes that would alter an existing dimension's generator are restart/new-world-only unless a safe migration is explicitly implemented.

## 10. Staged implementation and acceptance

### Stage A — source audit and contracts
- Inventory current dimension registration, world presets, Genesis stages, map import, terrain scanning, Planet/Atlas registry and persistence.
- Identify the exact existing map provider and its supported Forge/Minecraft version.
- Produce a compatibility matrix for the complete required mod set.
- Do not write a parallel dimension manager if a compatible existing owner already exists.

### Stage B — two-dimension bootstrap
- Register mapped and vanilla dimensions in one server/save.
- Verify both load, generate and persist independently.
- Verify the same mod set and common server services are active in both.
- Verify restart/reload does not recreate or reset either dimension.

### Stage C — shared Genesis
- Run the same geography → settlement → population → political generation stages for both dimensions.
- Prove that no campaign/objective/scenario is injected.
- Prove names, races/ancestries, political formation types and borders are sourced from installed data and stable registries.

### Stage D — continuity
- Transfer one Character between dimensions with unchanged UUID, inventory, family, ownership and relationships.
- Inject restart/failure between transfer phases and verify recovery without duplication or loss.

### Stage E — scale and integration
- Publish both planets and their strategic maps to Atlas.
- Validate per-dimension simulation LOD, chunk budgets and army/NPC scheduling.
- Run one combined Forge server boot with the exact mod set, then separate module builds and focused tests.

### Definition of Done
- One server process and one save contain both initial dimensions.
- Mapped and vanilla terrain providers produce their respective real terrain.
- The same Genesis pipeline produces geography, settlements, inhabitants and political organization in both.
- No campaign or forced player objective is created.
- Global Character identity and universe-level simulation survive dimension travel and restart.
- Atlas and Platform display both planets through shared stable IDs without duplicate catalogues.
- CI evidence is reported separately from actual in-game saved-world acceptance.
