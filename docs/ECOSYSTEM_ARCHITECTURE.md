# MAQAN UniverseForge — ecosystem architecture

## Product boundaries

| Product | Owns | Must not own |
|---|---|---|
| Atlas | Cosmic hierarchy, stars, systems, celestial bodies, orbital state, spatial maps, routes, exploration and navigation | Dynasties, settlement simulation, population, armies or realm succession |
| WorldForge core | World state, realm/title/border primitives, common gameplay APIs, event journal and shared UI primitives | Cosmic catalogue or a second galaxy navigator |
| WorldForge addons | Their named gameplay domains: origins, progression, genesis, settlements, people, court, war, army, trade, quests, etc. | Global ownership of unrelated domains |
| MAQAN Platform | Future desktop-like shell, dock, workspaces, window/layout orchestration and notifications | Gameplay simulation or forced coupling between mods |

## Integration rules

- Atlas and WorldForge remain independently loadable.
- Integration is capability-based and optional: discover a provider, check contract version, then expose a bridge.
- Missing provider means hide/disable the integration surface gracefully; never fabricate data.
- Atlas is the authority for cosmic location and travel context.
- WorldForge is the authority for political, demographic, settlement, military and cultural simulation.
- A future handoff may carry stable identifiers and location context, not duplicate state.
- Avoid direct dependencies between peer addons. Use WorldForge core APIs and soft bridges.

## Build boundaries

Every mod retains its own Gradle project, Forge dependency, Java toolchain, mod metadata and artifact. Root Gradle tasks orchestrate independent builds only. Current Forge patch versions intentionally differ and are not silently normalized.

## Atlas stabilization checklist

1. Preserve `maqan_galactic_navigator` mod id and package namespace during the first ecosystem migration.
2. Keep Cosmic Horizons as the source of celestial-system/body data; do not inject demo bodies or substitute a fake built-in galaxy.
3. Separate source data from user presentation/orbit overrides.
4. Use one authoritative simulation time/state for UI and in-world renderers.
5. Keep coordinate transforms and scale conversions centralized and tested.
6. Make route planning consume live body positions and server-validated destinations.
7. Treat surface exploration as discovered data; never reveal unvisited terrain as explored.
8. Fail visibly and safely when Cosmic Horizons data is absent, malformed or ambiguous.
9. Keep native Rust optional until platform binaries, JNI loading and fallback behavior are verified.
10. Add integration tests against the exact Cosmic Horizons version and datapack schemas.

## First migration policy

This repository migration preserves source files and existing mod identities. Refactors that change save formats, identifiers, runtime dependencies or public APIs require separate compatibility decisions and tests.
