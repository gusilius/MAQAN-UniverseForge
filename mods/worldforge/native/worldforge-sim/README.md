# WorldForge Simulation Core

Pure-Rust domain core for deterministic WorldForge simulation.

Current slices:
- Settlement food ledger advancement with bounded integer arithmetic.
- RTS tactical proposal evaluation from immutable aggregate squad snapshots.
- Allocation-free tactical batch evaluation into caller-owned reusable buffers.
- Allocation-free formation slot generation, world-space placement and role-aware assignment into caller-owned buffers.

Run locally with `cargo test --locked` from this directory. The crate is deliberately independent of Forge, Minecraft entities, JNI, filesystem, clocks and networking. Java remains authoritative for identity, command permissions, persistence and applying accepted orders. The tactical API is not yet wired to a Java/native adapter; do not describe its proposals as live in-game AI.
