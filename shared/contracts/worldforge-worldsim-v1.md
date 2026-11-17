# WorldForge Autonomous Simulation Contract v1

Status: active draft for `wf_worldsim` and `worldforge-worldsim`.

## Authority
- Java/Forge owns canonical world state, Character IDs, Minecraft entities/blocks, permissions, save files and domain execution.
- Rust owns deterministic calculations, macro-generation proposals, decision scoring and diagnostics.
- Rust must not mutate Minecraft or claim an action succeeded.
- Java validates every proposal against current state immediately before execution.

## Snapshot JSON
Required top-level fields:
- `world_id`: non-empty stable world identifier;
- `seed`: unsigned deterministic seed;
- `day`: canonical non-negative world day;
- `realms`, `borders`, `settlements`, `characters`, `workforce`: stable collections.

Collections must be sorted by stable IDs before submission. Missing optional source facts must be represented by explicit neutral values or omitted from the snapshot adapter's domain; do not invent observed facts.

## Proposal JSON
Each proposal contains:
- stable `id`;
- source `day`;
- `kind.type`;
- decision-specific fields;
- reason text that explains the state features used.

Proposals are recommendations until Java validates and executes them. Execution receipts must be idempotent and persisted with the owning domain.

## Determinism
Same seed + same validated snapshot + same engine version must produce byte-equivalent decision ordering and equivalent proposal payloads. Use stable sorting and seeded hashes; do not depend on hash-map iteration or wall-clock time.

## Error handling
Invalid world IDs, duplicate realm IDs, unknown realm references, negative resource stocks and malformed schema are rejected. Native load failure or malformed output must leave the world unchanged. The Java host records a diagnostic and may continue with already-authoritative addon simulation.

## Generation requests
Generation input includes seed, dimensions, realm count and provinces per realm. The initial Rust macro-generator returns realm, province and settlement-site seeds. Materialization into MapData, MapChunkGenerator, TitleData and Minecraft chunks is a separate Java transaction and must never overwrite an existing world.
