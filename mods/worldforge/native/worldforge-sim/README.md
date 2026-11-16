# WorldForge Simulation Core

Pure-Rust domain core for deterministic settlement and tactical simulation proposals.

Run locally with `cargo test --locked`. The crate is deliberately independent of Forge and JNI. It currently contains the fixed-point settlement food ledger and a deterministic single-squad tactical proposal function. Tactical output is advisory only for player-controlled squads; Java must re-check command authority and source revisions before applying any result. See `docs/RUST_RUNTIME_STRATEGY.md`, `shared/contracts/worldforge-simulation-v1.md` and `shared/contracts/worldforge-tactical-v1.md` for boundary rules. JNI and Java runtime integration are not implemented yet.
