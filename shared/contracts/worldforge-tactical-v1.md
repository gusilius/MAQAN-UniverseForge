# WorldForge Tactical Compute Contract v1

Status: domain contract and pure-Rust proposal engine implemented; Java/native runtime integration is not enabled.

## Ownership

- `wf-people` owns canonical Character identity and lifecycle.
- `wf-troops` owns squad membership, physical troop projections, orders, formations, command permissions and authoritative application.
- `wf-army` owns strategic armies, campaign roster and strategic supply.
- `wf-settlements` owns recruitment sites, workforce, stock and building state.
- Rust `worldforge-sim::tactical` accepts copied numeric/enumerated snapshots only. It does not access Minecraft, Forge, entities, registries, persistence, networking or clocks.
- UI is a view/controller and never owns a duplicate roster.

## Snapshot boundary

A snapshot is one squad's aggregate state captured coherently on the server thread. It contains a monotonic source revision, effective command mode, bounded morale/supply/cohesion/flank scores, own and visible-threat strength, objective distance/leash and explicit action permissions.

Do not send Minecraft object references, entity lists, mutable collections, or a second copy of Character records to Rust. Use canonical Character global IDs in Java for commander resolution; Rust needs only sanitized numeric leadership modifiers in a later schema.

## Formation compute boundary

The Rust `worldforge-sim::formation` module may calculate local slot coordinates, rotate/translate them into world X/Z coordinates, and assign roles to slots. It accepts primitive slices and caller-owned output/scratch buffers only. Java remains responsible for entity-to-role snapshots, terrain/collision/path validation, chunk availability and issuing movement targets.

The current Java formation implementation remains authoritative until the native adapter has parity tests for every formation type. In particular, the Rust `LOOSE` formation is deterministic but does not currently reproduce Java's seeded `Random` sequence; do not switch existing saved squads silently.

## Decisions and authority

Rust returns one deterministic proposal per squad: maintain, reform, engage visible threat, pursue, retreat or reposition. It echoes the exact source revision. Player-direct mode is advisory only.

Java must independently re-check, immediately before applying:
1. squad and order revision;
2. effective hierarchy policy and current commander;
3. action permission and player reclaim state;
4. current target validity, world/dimension and objective leash;
5. Character lifecycle and squad membership where relevant.

Apply accepted proposals through existing `Squads`, `ArmyHierarchy`, `Formations` and `TroopController` owners. No second order queue, army roster or entity controller.

## Performance and failure handling

- Use `evaluate_tactical_batch_into` with a reusable caller-owned output buffer to avoid per-batch result allocation.
- Reject undersized buffers before writing any result.
- Preserve input order and validate every echoed revision independently.
- Bound batch size at the future adapter boundary; split oversized batches deterministically.
- Recompute only on relevant state revision changes, order changes, meaningful threat changes or a bounded tactical cadence. Never add a busy loop or a competing scheduler.
- If native computation is unavailable, stale, malformed or fails validation, retain the existing Java order execution. Never invent an order.
- The current Rust crate is safe Rust and forbids unsafe code. JNI/FFI must be a separately reviewed integration, not introduced casually into this pure domain crate.

## Cross-addon compatibility

- Troop recruitment must continue to use completed settlement recruitment sites and existing settlement stock/payment owners.
- Strategic army muster/return must continue through `wf-army` roster ownership and current rollback behavior.
- Death, estate, morale and Character lifecycle effects must continue through `wf-people` and existing durable military lifecycle mechanisms.
- Treasury and supply changes must remain with their existing ledger/settlement owners.
- Optional addons must degrade explicitly when absent; never fabricate zero-valued identities, commanders or supplies.

## Current implementation

The Rust module implements deterministic single-squad proposals and an allocation-free batch API into a caller-owned output slice. Unit tests cover player authority, risk thresholds, retreat/pursuit permissions, 4096-entry batch ordering/revisions, undersized output and empty batches.

Not yet implemented: Java snapshot builder, JNI/native adapter, scheduler integration, decision application, corps coordination, target/terrain pathfinding, or in-game tactical AI. This contract intentionally does not claim those features are live.
