# MAQAN Hybrid Architecture

## Product constraints

1. MAQAN runs inside the Minecraft client. The user does not install Rust, Cargo, a second game engine, or a separate application.
2. Existing Cosmic Horizons cube planet models and their resource IDs remain authoritative. The first hybrid milestones must not replace them with sphere models.
3. Cosmic Horizons and enabled datapacks are the only source of celestial objects and system metadata. No bundled galaxy, demo bodies, or static fallback catalogue is permitted.
4. The galaxy atlas, system view, planet explorer, and surface map are views over the same source identity and clock.
5. Never mark a surface chunk as explored merely because it was loaded by render distance. Exploration must be tied to player visitation or an explicit exploration rule.

## Runtime ownership

### Java / Forge
- Forge lifecycle, screens, input, network, dimensions and server-authoritative travel.
- Cosmic Horizons reflection adapter and source JSON.
- Existing cube model / texture rendering.
- Minecraft world and chunk access.
- Persistent client/server save integration.

### MAQAN presentation layer
- Shared design tokens, typography, panels, buttons, focus states and transitions.
- Galaxy / system atlas and body inspector.
- Surface map, underground layers, waypoints and visited-area overlays.
- Camera and selection state shared between atlas views.

### Rust native core
- Pure data processing and indexing where profiling shows a benefit.
- No independent window and no second renderer in the first implementation.
- JNI is a later integration step. A native library must be built for each supported OS/architecture, packaged in the mod, loaded defensively, and tested with the Minecraft graphics context before it can be enabled.
- Java fallback must remain available if native loading fails.

## Surface-map data model

The first native core stores a sparse index keyed by dimension identity and chunk x/z. Each record contains a compact surface color, sampled height and flags. The index does not generate missing tiles. Java remains responsible for reading Minecraft world data and deciding when a chunk qualifies as visited.

Future milestones:
1. Java client tracker records only player-explored chunks.
2. Versioned per-world/per-dimension persistence and migration.
3. Map renderer displays only recorded tiles; no fog-of-war bypass.
4. Optional underground layer snapshots and user waypoints.
5. Rust acceleration only after profiling the Java implementation and defining a stable JNI contract.

## Current implementation status

- Rust crate: sparse exploration-tile index and unit tests.
- Rust CI: cargo test --locked.
- Not implemented yet: JNI loading, native binaries, Java-to-Rust calls, surface capture, map UI, server-shared exploration.
- The crate is intentionally dependency-free and does not start a process or open a window.
