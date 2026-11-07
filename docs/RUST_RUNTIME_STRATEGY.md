# MAQAN Rust Runtime Strategy

## Decision

Rust is a shared implementation technology, not a requirement for either mod to start. Atlas and WorldForge remain independently installable Forge mods. Each native runtime is optional and must have a Java fallback or a clear feature-level degradation path.

## Responsibilities

| Layer | Atlas | WorldForge |
|---|---|---|
| Java / Forge | lifecycle, Cosmic Horizons integration, rendering, screens, networking, world/dimension handoff | entities, blocks, events, screens, Forge lifecycle, persistence adapters, networking |
| Rust | deterministic orbital propagation, coordinate transforms, geometry and large spatial calculations | deterministic population/economy ticks, logistics, strategic AI evaluation, large-scale war/supply calculations |
| Shared contracts | versioned spatial snapshots and motion results | versioned settlement, population, economy and order snapshots |

Do not move Minecraft-owned objects, registries, level access, rendering, or save-file ownership into Rust. Rust receives immutable snapshots and returns deterministic deltas. Java validates and applies those deltas on the correct game thread.

## WorldForge native slice 0.1

`mods/worldforge/native/worldforge-sim` is the first isolated Rust simulation crate. It currently implements a deterministic fixed-point food ledger with explicit elapsed days, capacity limits, shortage reporting and unit tests. It has no JNI or external dependencies yet. This is intentional: validate domain rules before binding the engine to Forge.

## Native boundary rules

1. No Rust thread may access Minecraft or Forge objects.
2. Inputs are immutable snapshots with schema versions and stable entity IDs.
3. Outputs are deltas, not direct mutations of world state.
4. All quantities crossing the boundary use documented units and fixed-point precision where deterministic replay matters.
5. Simulation time is supplied by the host; no wall-clock reads in domain code.
6. Native loading is optional. Missing library, unsupported architecture, or ABI mismatch must fall back to Java behavior.
7. JNI is introduced only after the pure Rust crate and Java adapter contract have tests.
8. Save compatibility is defined by Java-owned versioned data, not Rust struct layout.

## Planned slices

- WFS-01: deterministic food/economy ledger (started).
- WFS-02: workforce allocation and production chains.
- WFS-03: population cohorts, needs, migration and household lifecycle.
- WFS-04: route capacity, trade flows and logistics.
- WFS-05: army supply, movement costs and siege pressure.
- WFS-06: strategic AI scoring and event proposals.
- WFS-07: JNI adapter, capability detection and Java fallback.

Atlas remains the first native integration target because it already contains `maqan-core` and a Java bridge. Its native motion implementation must be audited for parity with `CosmicMotionClock` and the in-game Cosmic Horizons controller before changing simulation ownership.
