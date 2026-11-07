# Shared contracts

Reserved for versioned, optional APIs shared across independently installable MAQAN modules. A contract contains stable identifiers, typed references, events or data-transfer types only. It must not depend on Forge client classes, own gameplay state, or force Atlas and WorldForge to load together.

## Platform boundary

The shared MAQAN application shell and its UI/workspace lifecycle are specified in [MAQAN Platform architecture](../../docs/architecture/MAQAN_PLATFORM.md). This directory is **not** a place for UI widgets, theme implementations, window managers or renderer code. Platform UI contracts belong to the platform layer; this directory remains for optional cross-mod/domain interoperability.

## Initial integration candidates

- `CosmicLocationRef`: universe / galaxy / sector / system / body identifiers.
- `WorldLocationRef`: dimension key and block/chunk coordinates.
- `EntityPoliticalRef`: optional WorldForge realm / settlement / character references.
- `TravelContext`: source, destination and handoff metadata.

## Rules

- Keep contracts versioned and backward-compatible where possible.
- Use qualified stable IDs; local IDs alone may collide across modules.
- Do not put renderer state, UI state, mutable simulation state or Forge client objects in shared DTOs.
- Do not implement these as mandatory runtime dependencies until both sides have adapters and compatibility tests.
