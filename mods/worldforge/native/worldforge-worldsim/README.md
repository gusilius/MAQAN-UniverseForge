# WorldForge Autonomous World Simulation

Pure deterministic Rust decision engine for the WorldForge Minecraft mod stack.

The host supplies an immutable, sorted world snapshot. The engine returns stable-ID proposals with reasons; Java validates and applies each proposal through existing domain APIs. Rust never mutates Minecraft state or persists authoritative game data.

Initial domains: emergent border-war scoring, settlement project selection, workforce reallocation, frontier recruitment proposals, and cross-realm dynastic matching. This is a decision core, not yet the full world generator or live Forge adapter.

Run `cargo test --locked` from this directory. The crate intentionally uses only the Rust standard library so the first integration is reproducible offline.