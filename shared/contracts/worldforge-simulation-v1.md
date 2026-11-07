# WorldForge Simulation Contract v1

Status: draft contract for the Java/Rust boundary.

## Ownership

Java/Forge owns authoritative world state, persistence, entity identity, permissions and application of results. Rust computes deterministic proposals from immutable snapshots. Rust never writes Minecraft state.

## Snapshot envelope

Every request must carry:

- `schema_version`: integer, currently `1`;
- `world_id`: stable world identifier;
- `simulation_tick`: authoritative integer tick supplied by the host;
- `elapsed_ticks`: non-negative integer interval;
- `request_id`: stable idempotency key;
- `entities`: stable-ID keyed snapshots, sorted before deterministic processing.

## Result envelope

Every response must carry:

- matching `schema_version`, `world_id`, `simulation_tick`, and `request_id`;
- `status`: `ok`, `partial`, or `unsupported`;
- `deltas`: proposed state changes keyed by stable entity ID;
- `diagnostics`: machine-readable warnings and rejected inputs.

## Numeric rules

- Resource amounts use signed 64-bit fixed-point integers; 100 subunits equal one displayed unit unless a domain declares another scale.
- Negative stock, capacity, workforce, or elapsed time is invalid unless a domain explicitly permits debt.
- Overflow must saturate or return a diagnostic; never silently wrap.
- Iteration order is stable and independent of hash-map iteration.

## Compatibility

Unknown optional fields are ignored. Unknown schema major versions are rejected. Java validates every returned delta against current authoritative state before applying it. If the native engine is unavailable or returns an invalid response, Java fallback logic remains authoritative.
