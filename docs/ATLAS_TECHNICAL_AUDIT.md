# Atlas technical audit — migration baseline

## Confirmed in source

- `CosmicHorizonsScanner` reads the synchronized Cosmic Horizons world-variable datapack list and refreshes the catalogue when its payload changes.
- `CosmicSceneEngine` creates immutable scene snapshots and keeps global system placement separate from local body positions.
- `NativeOrbitalSimulation` is present as the orbital simulation authority; it consumes the shared `CosmicMotionClock`.
- `CosmicMotionClock` advances once per observed game-time delta, so multiple render paths sampling the same tick do not independently advance the phase.
- Existing UI and world rendering are separate code paths, with dedicated renderers for GUI bodies, orbital/system views and cosmic environment geometry.
- Route planning includes dynamic route planning, moving-target interception, obstacle catalogues and arrival planning.
- Surface exploration, terrain preview, waypoints, player markers and workspace persistence already have dedicated classes.
- Rust JNI/core source and a Java layout fallback are both present. Rust is not assumed to be available at runtime merely because source files exist.

## Important compatibility decisions

- Keep the existing `maqan_galactic_navigator` mod id and `com.maqan.galacticnavigator` package during this migration.
- Atlas is branded as Atlas in the artifact and mod display name. Existing mod identity is not renamed.
- Keep Forge `1.20.1-47.4.28` for Atlas. WorldForge remains on `1.20.1-47.3.0` until a deliberate compatibility test justifies alignment.
- Keep Cosmic Horizons integration reflective/optional unless the mod's actual required-dependency contract is deliberately changed.
- Do not insert synthetic systems, planets or placeholder bodies when the Cosmic Horizons catalogue is empty.

## Risks to validate in the next engineering pass

1. **Clock authority across all renderers:** verify every in-world Cosmic Horizons integration point samples the same effective simulation time and that pause, dimension switch, world reload and time rollback behave correctly.
2. **Orbital model parity:** compare Navigator preview positions against the in-world orbit controller for the same body, epoch, parent and override settings.
3. **Coordinate conventions:** document and test axis direction, units, origin and scale between Cosmic Horizons physical JSON, GUI model positions, local orbital positions and global atlas placement.
4. **Parent resolution:** test moons, binary/multiple-star systems, missing parents, cycles and duplicate body identifiers.
5. **Catalog reload:** test datapack reload, joining/leaving worlds, server sync delay and changed datapack payloads.
6. **Route validity:** test moving destinations, dimension handoff, unavailable dimensions, stale waypoints and server-side destination validation.
7. **Surface map truthfulness:** test exploration persistence and ensure unknown terrain is not presented as surveyed.
8. **Native library lifecycle:** test absent, incompatible and successfully loaded native binaries on Windows/Linux/macOS; Java fallback must remain usable.
9. **Large-scale atlas performance:** profile hundreds/thousands of systems and high body counts; retain complete catalogue data while virtualizing rendering and expensive calculations.
10. **Cosmic Horizons version contract:** validate specifically against the target 0.0.7.3 build and the user's active datapacks.

## Not claimed as complete

This audit confirms code presence and architectural boundaries, not correctness of every feature. No Gradle build, Rust build, Forge launch or in-game Cosmic Horizons integration test could be run in this environment because Gradle and Cargo are not installed.


## Atlas UI visual pass — stage 1

- Shared Java theme and Rust draw-command palette now use a restrained dark/cyan system.
- Decorative starfield, nebula blooms, sweeping background beam, corner brackets, panel bevels and selection glows were removed from workspace chrome.
- Selected/hovered states remain visible through thin rails, quiet surface tints and single-pixel accent rules.
- Celestial body renderers, cuboid planet geometry, orbit simulation, map coordinates, navigation state, input and persistence code were not modified in this pass.
- Rust unit expectations were updated to test the new static chrome and minimal state signals.

This is a visual foundation pass, not yet a full component extraction of `NavigatorScreen`. The next pass should separate its existing regions while keeping the same models and interaction paths.
