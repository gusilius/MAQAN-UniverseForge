# Atlas / WorldForge Integration Contracts

## Ownership

- Atlas is authoritative for cosmic object identity, system topology, celestial coordinates, orbital data and travel graph.
- WorldForge is authoritative for civilization entities, characters, realms, settlements, populations, economies, diplomacy, armies and histories.
- MAQAN platform shell owns windowing, workspaces, global presentation preferences and cross-mod navigation affordances; it does not own gameplay simulation.

## Optional integration

Each mod must load and function without the other. Integration is discovered through optional capability registration, not hard dependencies. Missing providers must degrade gracefully.

## Stable identifiers

Use namespaced, versioned identifiers. Never use display names as primary keys. Mappings from Atlas body IDs to WorldForge world/realm IDs must be explicit, persisted and validated.

## Events and snapshots

Contracts should distinguish:
- immutable identity and metadata;
- versioned state snapshots;
- domain events with timestamp and source;
- commands with permission checks and result/error states.

Events must be idempotent or carry unique event IDs. Consumers must tolerate missing, stale or out-of-order snapshots. Do not expose mutable internal implementation objects across mod boundaries.

## UI contributions

A mod/addon may register:
- workspace contributions;
- context inspector tabs for supported entity types;
- Dock actions;
- map layers;
- commands/actions with permission and availability predicates;
- search providers and entity links.

Contributions declare stable IDs, localization keys, required capabilities, ordering hints and lifecycle cleanup. The platform must not hardcode every addon panel into one monolithic screen.

## Versioning and compatibility

Contracts use semantic versioning and explicit compatibility ranges. Breaking changes require a new major contract version or adapter. Each mod remains independently releasable. Integration tests cover Atlas-only, WorldForge-only and both-installed configurations.

## Data authority and truthfulness

Never fabricate unavailable values. Include source, timestamp and confidence/quality where relevant. A UI may show approximate or schematic data only when clearly labeled as such.
