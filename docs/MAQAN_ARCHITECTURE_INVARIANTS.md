# MAQAN UniverseForge — Architectural Invariants

Status: mandatory architecture contract for Stages VII–XII and all subsequent integrations.

These rules are invariants, not optional conventions. A feature that violates one is not complete even if it builds, renders a screen, or passes isolated unit tests.

## 1. Character is the only identity

A person has exactly one canonical character identity in a world. Worker, soldier, hero, ruler, courtier, family member, prisoner, and profession are roles, memberships, assignments, titles, or lifecycle states attached to that identity. They are not separate competing person records.

Required model:
- Stable immutable CharacterId, scoped by a stable WorldId (or globally unique with an explicit world ownership contract).
- One authoritative Character aggregate/registry and lifecycle.
- Roles and appointments are independently attachable/removable records referencing CharacterId.
- Families, armies, settlements, courts, titles, factions, and jobs reference CharacterId; they do not own shadow copies of a person.
- Map, settlement, court, army, hero and population views resolve the same CharacterId.
- Cross-map transfer changes location/ownership relationships, never identity.
- Deduplication, migration and save/load preserve identity. A display name is never an identity key.

Acceptance tests:
1. The same character shown as worker, soldier and hero resolves to one CharacterId.
2. Assigning/removing a role does not create/delete the character.
3. Moving a character between maps/settlements does not change identity.
4. Family, court, army and inspector references resolve the same authoritative character.
5. Save/load and migration do not duplicate or orphan characters.

## 2. One resource, one source of truth

Every authoritative game concept has one owning service and one persisted source of truth. UI modules and projections may cache or derive data, but must not independently own competing copies.

Examples:
- One treasury/ledger per defined polity or economic owner; balances are derived from reconciled transactions, not separately mutated UI counters.
- One world calendar/time authority; local calendars are views or explicit time-zone/scale projections.
- One canonical skill/progression state per character; class, profession and hero screens are projections/adapters over it.
- One needs/condition state per character; population, court, army and character views consume the same state.
- One inventory/stockpile/ownership authority per defined container/owner; market and logistics views reference it.
- Atlas owns canonical geography and spatial identity; other modules reference Atlas IDs and publish domain overlays.

Every cached or materialized view must declare its owner, invalidation/update mechanism and whether it is rebuildable. Never silently write to a projection as if it were authoritative.

Acceptance tests:
1. Mutating a canonical value is reflected in every subscribed view.
2. Rebuilding projections from authoritative state produces equivalent values.
3. Two modules cannot independently commit conflicting balances, dates, skills or needs.
4. Ledger replay/reconciliation recovers balances and detects duplicate/missing transactions.

## 3. Simulation must execute and confirm actions

An AI intention, plan, path, order or animation is not completed work. It becomes completed only after the authoritative owner accepts and commits the action, and reports its result.

Required action lifecycle:
Proposed -> Validated -> Accepted -> Executing -> Committed
with explicit terminal alternatives Rejected, Failed, Cancelled, and Expired.

Every action carries:
- Stable action/task ID and idempotency key.
- Actor CharacterId or organization ID.
- Target/resource IDs and expected revision/version where relevant.
- Issuer, authority/permission context, world/time context and preconditions.
- Result/receipt with committed revision, actual resource deltas and failure reason.

The owning domain service validates preconditions, permissions, reachability/capacity, resource availability and concurrency; performs the mutation transactionally; emits a durable result/event. AI consumes the result and replans on rejection/failure. Retries must be idempotent.

Acceptance tests:
1. An AI plan without an accepted/committed receipt never marks a task complete.
2. Insufficient resources, invalid authority or stale revisions reject the action without partial mutation.
3. Replaying the same idempotency key cannot double-spend or duplicate production.
4. Failed movement/transport does not teleport goods or mark delivery complete.
5. Save/load restores in-flight tasks and their execution state safely.

## 4. Atlas is the sole canonical geography

Atlas owns canonical spatial identity, coordinate reference systems, world/planet/map hierarchy, terrain and authoritative geographic transforms. WorldForge owns domain simulation (settlements, borders, armies, population, events) and publishes georeferenced domain data to Atlas; it must not create a parallel canonical map or independent coordinate truth.

Required contracts:
- Stable IDs for universe, galaxy, system, planet/world, region, map tile/cell and geographic features.
- Explicit coordinate reference system, units, origin, axis orientation, bounds, scale and transform version.
- Live/versioned spatial snapshots or streams with revision, timestamp and source.
- Domain overlays carry Atlas spatial IDs or coordinates in the declared reference system.
- Selection, map layers, spatial queries and entity inspectors use the same IDs/transforms.
- Raster images are derived visual assets/caches only, never the authoritative spatial database.
- Export/import is an interchange mechanism, not the live synchronization architecture.

Acceptance tests:
1. The same settlement/army/territory resolves to the same Atlas location in every view.
2. WorldForge overlay changes appear in Atlas without manual raster export.
3. Coordinate round trips remain within documented tolerance across zoom/scale transforms.
4. Stale revisions are detected; missing/unloaded chunks have explicit behavior.
5. Atlas remains the single source of geography when WorldForge is absent.

## 5. Platform is the sole workspace and UI host

Platform owns the unified shell, docking/workspaces, navigation, shared component library, selection presentation, inspector host, search presentation, notifications, shortcuts and accessibility behavior.

Feature modules own domain logic, authoritative data, commands, validation and persistence. They contribute typed providers, view factories, inspector sections, search sources, actions, map layers and event subscriptions through versioned APIs. They must not launch competing top-level workspaces or duplicate global navigation.

Rules:
- A provider contribution is not a second source of truth.
- All domain mutations go through the owning service/command API and return a typed result.
- Every visible route must mount a real embeddable view or be explicitly declared a non-UI command. No placeholder route or decorative preview.
- Provider registration/unregistration is lifecycle-safe and removes its views, indexes, actions, shortcuts, layers and subscriptions.
- Shared components are versioned, documented, accessible and used by migrated legacy screens.
- Platform can operate with optional providers absent; missing capabilities are reported honestly and never silently represented as functional.

Acceptance tests:
1. Every installed module is discoverable and registers its real contributions exactly once.
2. Opening a capability stays in the Platform workspace unless the capability is explicitly a non-UI action.
3. Provider unload removes all contributions and does not leave stale selections/search entries/actions.
4. An inspector action invokes the domain command and displays the actual result/rejection.
5. Keyboard, pointer, scaling and accessibility behavior are consistent across migrated views.

## 6. Cross-system integration matrix

| Domain | Authority | Platform/Atlas integration |
|---|---|---|
| Character identity, roles, family | World Character Registry | Search, inspector, selection, map focus |
| AI plans and task execution | Simulation/task execution services | Orders, status, receipts, notifications |
| Treasury, prices, trade, stockpiles | Economy/ledger services | Typed inspector, market views, event feed |
| Time/calendar | World time service | Shared time display and scheduling |
| Skills, needs, progression | Character/progression services | One character inspector and role-specific views |
| Geography and spatial identity | Atlas | Live layers, map selection, spatial queries |
| Settlements, borders, armies, population | WorldForge domain services | Atlas overlays and Platform embedded views |
| Docking, navigation, search, inspector host | Platform | Unified workspace |

## 7. Delivery and verification policy

- Do not mark a stage complete based on a design document, registry stub, mock provider, screen route, successful compile, or isolated unit test alone.
- Every integration must identify the authoritative owner, stable IDs, API contract, persistence boundary, lifecycle and failure behavior.
- Implement vertical slices end-to-end: domain mutation -> authoritative commit -> event/receipt -> search/inspector/map projection -> save/load.
- Add unit, integration, migration, lifecycle and in-game acceptance tests.
- Preserve optional-module behavior and verify both with and without Platform installed.
- Record verified status as Implemented, Partial, Missing or Blocked, with evidence. Never label unverified functionality complete.

## 8. Required next implementation sequence

1. Inventory authoritative data owners and duplicate models across all modules.
2. Establish WorldId and canonical CharacterId registry; migrate role-specific person records to references/roles.
3. Establish shared resource/time/skills/needs authorities and transaction/ledger reconciliation.
4. Establish action/task execution receipts and migrate AI/RTS orders to confirmed execution.
5. Make Atlas the canonical spatial service; migrate WorldForge maps to Atlas IDs and live overlays.
6. Complete Platform provider lifecycle and typed contracts; register all installed modules.
7. Complete global search, universal inspector, context actions, durable notifications and embedded view factories.
8. Migrate legacy screens to the shared component library and complete responsive/accessibility acceptance.

Do not skip data-authority work by implementing UI adapters over duplicate models. If an owning service does not exist yet, mark the adapter blocked and implement the owner first.
