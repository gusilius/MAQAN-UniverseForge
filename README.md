# MAQAN UniverseForge

MAQAN UniverseForge is a multi-mod ecosystem built around two independently installable Minecraft mods:

- **Atlas** — cosmic geography, star systems, orbital mechanics, planetary navigation and travel.
- **WorldForge** — a living-world simulation for settlements, populations, economies, realms, dynasties, diplomacy, armies and warfare.
- **MAQAN Platform** — an optional shared desktop-style shell mod; gameplay modules remain independently installable.

The shared MAQAN platform experience is a desktop-style workspace shell. It is a presentation and integration layer, not a monolithic gameplay mod. Atlas and WorldForge retain independent mod IDs, lifecycle, configuration, saves, dependencies and release artifacts.

## Product direction

MAQAN is designed as a strategy and simulation platform rather than a collection of Minecraft-style screens.

Its interface combines:
- a desktop shell with workspace tabs, global clock, notifications and settings;
- persistent bottom Dock and independently retractable left/right sidebars;
- movable, resizable, dockable and groupable tool windows;
- context-sensitive object inspectors;
- an Atlas spatial workspace with universe-to-orbit navigation;
- WorldForge management surfaces inspired by the simulation depth of Manor Lords, Crusader Kings III and Mount & Blade II: Bannerlord.

## Non-negotiable architecture rules

1. Atlas and WorldForge remain separate mods and produce separate JARs.
2. Either mod must work when installed alone.
3. Shared code is limited to stable contracts, identifiers and small utilities.
4. WorldForge owns civilization, settlement, population, economy, politics and military simulation.
5. Atlas owns cosmic spatial data, stars, systems, celestial bodies, orbits and travel.
6. The UI must expose real system state; it must not fabricate data or present decorative controls as functional.
7. Existing modules, addons, data packs and gameplay capabilities must be preserved during migration.

## Documentation

- [Platform UX architecture](docs/PLATFORM_UX_ARCHITECTURE.md)
- [WorldForge management surfaces](docs/WORLDFORGE_GAMEPLAY_SURFACES.md)
- [Atlas spatial workspace](docs/ATLAS_SPATIAL_WORKSPACE.md)
- [Cross-mod integration contracts](docs/INTEGRATION_CONTRACTS.md)

## Status

The repository is the destination for the clean-history consolidation. The architecture documents define the target experience; they do not claim that the UI or gameplay systems described are already implemented.
