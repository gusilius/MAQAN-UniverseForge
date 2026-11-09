# Spatial Strategy Contract v1

## Purpose

Atlas, Settlement and RTS share one dimension/block coordinate frame. The server owns world state; Atlas consumes a validated, renderer-neutral snapshot and publishes selections to the shared MAQAN inspector.

## Canonical coordinate system

- Spatial identity is scoped by the Minecraft dimension resource ID (for example `minecraft:overworld`), never by dimension display name.
- Horizontal positions are absolute Minecraft block coordinates: X east/west, Z south/north. Y is the sampled surface or entity height.
- Settlement planning coordinates remain local offsets from `Colony.cx/cz`; the export adapter converts them to absolute block coordinates exactly once.
- WorldForge image maps are centered at block coordinate (0, 0), with their map pixel scale and origin converted by `MapData.bpp`.
- Geometry is serialized in absolute block coordinates. Atlas divides by 16 only at the chunk-space camera projection boundary.

## Shared snapshot

The existing `map_snapshot.json` format remains additive and backward-compatible with schema version 1:
- `terrain[]`: one bounded overview raster per WorldForge image dimension. Each raster has a regular sample grid, pixel step, blocks-per-pixel, world origin, height samples, biome indexes/palette/colors and hydrography values.
- `territories[]`: authoritative province polygons, including multiple rings for islands and holes.
- `political_boundaries[]`: realm/state borders.
- `features[]`: point features plus optional `path[]` and `rings[][]` geometry.

The terrain exporter caps each dimension at 256 x 256 samples. Atlas validates array lengths, dimensions, coordinate finiteness, biome palette indexes and a global feature-geometry budget before publishing a snapshot. Invalid refreshes keep the last known-good snapshot.

## Ownership and adapters

| Domain | Authority | Atlas projection |
|---|---|---|
| Natural terrain, height, biome, rivers | WorldForge `MapData` | Terrain raster layers |
| Provinces and state borders | WorldForge `TitleData` | Territory and political boundary rings |
| Settlements and buildings | Settlement `Colony` / `Settlement` | Point anchors and building footprints |
| Land parcels | `SettlementMap` | Polygon rings |
| Roads | `SettlementMap.Road` | Ordered block-coordinate paths |
| Physical soldiers and armies | `TroopEntity` / `Squads` | Unit points and aggregate army anchors |
| Player exploration | Atlas exploration store | Separate fog-of-war layer; never overwritten by the planet overview |

Atlas is a read-only projection. It must not mutate authoritative settlement, army, territory or terrain state.

## Rendering and LOD

- Strategic scale: provinces, realms, settlement anchors and army aggregates.
- Regional scale: roads and settlement-level points.
- Local scale: land parcels and building footprints.
- Tactical/detail scale: individual soldiers and workers.
- Terrain raster drawing is culled to a conservative viewport bound and sample density is reduced when a sample would occupy less than two screen pixels.
- Layers can be toggled independently: biome, elevation and water/river data.
- Selection of a road, parcel or building footprint resolves to the original WorldForge feature ID and is published through the MAQAN platform selection bridge.

## Current limitations / acceptance gates

- Snapshot export is currently an explicit server command (`/worldforge export`) and the Atlas reader is a client-local file cache. This is not multiplayer network synchronization or a continuously live shared map feed.
- The current exporter enumerates loaded WorldForge image dimensions; non-WorldForge dimensions are not yet covered by the shared terrain snapshot.
- The settlement and troop contributors only appear in a snapshot after their addons are loaded and the export is run.
- Terrain raster is an overview layer; it does not replace the higher-detail explored-chunk fog-of-war map or the 3D terrain renderer.
- Road path rendering is a strategic visualization. Physical obstruction, bridge/tunnel construction, navigation costs and pathfinding across the whole planet still need a unified world-scale navigation service.
- LOD is currently render filtering, not a distributed simulation LOD. Remote settlement/army simulation cadence remains owned by WorldForge systems.

The stage is not accepted until snapshot refresh and transport are reliable for singleplayer and multiplayer, all spatial producers use the canonical coordinate frame, global pathfinding respects terrain/water/obstacles, and end-to-end Forge tests verify persistence, rendering, selection and cross-module identity.
