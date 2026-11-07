# MAQAN UniverseForge

**A modular MAQAN platform and Minecraft ecosystem repository.** MAQAN is designed as one shared application platform with a common interface and independently owned workspaces. Atlas and WorldForge remain separate Forge mods and build artifacts.

## Platform architecture

- `docs/architecture/MAQAN_PLATFORM.md` — authoritative concept for the shared MAQAN shell, design system, navigation, workspace lifecycle, selection, panels and module boundaries.
- `docs/architecture/ATLAS_WORKSPACE.md` — Atlas integration contract and the additive Unified Cosmic Scene mode.
- The shared shell owns global UI. Modules contribute workspace content and domain capabilities; modules must not create competing application shells.

## Repository map

- `mods/atlas` — Atlas domain: cosmic catalogue, Cosmic Horizons integration, orbital simulation, route planning, surface maps and spatial visualization. It is hosted as a MAQAN workspace.
- `mods/worldforge/wfmod` — WorldForge core: world map, realms/titles/borders, shared APIs, event and simulation infrastructure. It is hosted through the same platform workspace contract.
- `mods/worldforge/wfaddons/wf-*` — independent WorldForge gameplay addons.
- `shared/contracts` — versioned, optional cross-mod data/API contracts. No gameplay ownership is moved here.
- `shared/identifiers` — stable ecosystem identifiers.
- `shared/utilities` — dependency-free helpers only.
- `docs` — architecture and integration decisions.

## Independence guarantees

1. Atlas keeps mod id `maqan_galactic_navigator` to preserve existing installs and saved configuration compatibility.
2. WorldForge core keeps mod id `worldforge`; addons retain their own mod ids.
3. Atlas and WorldForge have separate Forge builds and output JARs.
4. WorldForge addons remain independently installable and communicate through the existing core APIs / soft integration.
5. No direct Atlas ↔ WorldForge dependency is introduced.
6. Shared contracts are optional and versioned; they do not become a mandatory monolithic runtime.
7. A shared MAQAN interface does not merge module data, simulation engines, mod IDs, classpaths or build outputs.

## Requirements

- Minecraft 1.20.1
- Java 17
- Gradle 8.x
- Forge versions are intentionally preserved per project for now: Atlas `47.4.28`; WorldForge `47.3.0`. Do not unify these without compatibility testing.

## Build

Run from this repository root:

```bash
gradle buildAtlas
gradle buildWorldForgeCore
gradle buildWorldForgeAddons
gradle buildEcosystem
```

`buildWorldForgeAddons` uses the addon's existing dependency-order builder. The root project orchestrates builds; it does not merge their mod IDs or classpaths.

## Windows target

The current CI target is Windows x64 only (`windows-latest`, Java 17, Rust MSVC). Atlas and WorldForge remain separate Forge projects and produce separate JARs.

## Important

A successful Gradle build is not an in-game compatibility test. Validate Atlas against the exact Cosmic Horizons build and active datapacks, then test both mods together and separately in clean Forge instances.
