# Build pipeline and native-code boundaries

## Build targets

Atlas and WorldForge are intentionally not collapsed into one Forge project.

- `mods/atlas` is built from its own Gradle project and keeps the Atlas mod ID.
- `mods/worldforge/wfmod` is the WorldForge core mod.
- `mods/worldforge/wfaddons` builds the independently installable WorldForge addons in dependency order.
- The root repository coordinates CI and documentation; it does not merge the two Forge lifecycles.

The import workflow pins the two source snapshots used for the initial consolidation. Once imported, development should happen in this repository rather than by silently pulling changing source branches into every build.

## Java / Rust responsibility split

### Java remains authoritative for Minecraft integration

Java owns:
- Forge mod entry points, registries, events and lifecycle;
- entities, blocks, items, capabilities and Minecraft world access;
- client screens, render integration and networking;
- world save/load, data packs and compatibility with other mods;
- addon discovery and optional-mod bridges.

WorldForge's existing core and addons remain Java-first. They are not being mechanically rewritten in Rust.

### Rust is an optional compute engine

Rust is appropriate only where profiling shows a measurable benefit and where the problem can be expressed as deterministic data-in/data-out computation, for example:
- large population and household simulation batches;
- economy and production-network calculations;
- route and logistics graph solving;
- large-scale AI planning or strategic simulation;
- Atlas orbital calculations and spatial indexing.

Rust must not directly own Minecraft objects or Forge lifecycle. Java passes immutable snapshots or compact DTO buffers to the native layer and applies validated results back on the server thread.

## Native bridge rules

1. Every native subsystem has a Java fallback or a clearly declared optional capability.
2. Native libraries are loaded defensively; missing or incompatible binaries must not crash mod discovery.
3. JNI calls must not retain Minecraft object references or call back into game state from worker threads.
4. Inputs and outputs are versioned, bounded and validated.
5. The Java implementation remains the correctness oracle until differential tests prove parity.
6. Native acceleration is enabled only after benchmarks show an actual improvement on representative workloads.

## First build gates

1. Import source trees without deleting legacy code, data or tests.
2. Compile/test Atlas Rust core on Linux, Windows and macOS CI runners.
3. Build the Atlas Forge JAR with the platform native artifacts embedded.
4. Run WorldForge logic tests.
5. Build WorldForge core and addons in their declared dependency order.
6. Upload the two products as separate CI artifacts.
7. Add a Forge client/server smoke test after the first clean build is green.

A green Java build is not a claim that in-game behavior is correct. Client and dedicated-server smoke tests remain separate acceptance gates.
