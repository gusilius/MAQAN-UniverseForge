# Shared contracts

Reserved for versioned, optional APIs shared across independently installable MAQAN mods. A contract must contain stable identifiers and data transfer types only. It must not depend on Forge client classes, own gameplay state, or force Atlas and WorldForge to load together.

Initial integration candidates:
- `CosmicLocationRef`: universe / galaxy / sector / system / body identifiers.
- `WorldLocationRef`: dimension key and block/chunk coordinates.
- `EntityPoliticalRef`: optional WorldForge realm / settlement / character references.
- `TravelContext`: source, destination and handoff metadata.

Do not implement these as mandatory runtime dependencies until both sides have adapters and compatibility tests.
