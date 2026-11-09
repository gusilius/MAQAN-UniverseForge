# Этап XII — AAA Workspace 2.0

## Что теперь является реальной реализацией

### Platform shell
- WorkspaceDockTree is the canonical dock layout model: nested horizontal/vertical splits, tab groups, tab activation, docking, grouping, removal/collapse and splitter resizing.
- PlatformShellScreen uses the tree to allocate the live map viewport, navigation, inspector, tools and timeline regions.
- Panel headers can be dragged onto another panel: edge drops split the target, center drops create a tab group. Splitter handles resize the actual layout.
- WorkspaceProfileStore persists versioned named layouts. PlatformShellScreen exposes workspaceProfiles, saveWorkspaceProfile, loadWorkspaceProfile and deleteWorkspaceProfile for a profile manager UI.
- The default profile is restored across launches. Legacy geometry preferences are still read for migration compatibility.

### Shared services
- WorkspaceRuntime provides entity search indexing, provider entity openers, typed inspector fields, contextual actions, a bounded event journal, event subscriptions, shortcut registration and provider unload cleanup.
- CapabilityRegistry retains the built-in catalogue and also supports runtime provider registration/unregistration.
- WorkspaceMapLayerRegistry supports provider map overlays, visibility and opacity preferences, and safely disables a failing overlay.
- WorkspaceUi contains shared panel/section/status/hit-test/scaling drawing primitives.
- The live shell renders provider map layers, provider inspector contributions, the event journal, global search (Ctrl+K), contextual actions (right click) and registered shortcuts.

## Provider integration contract

WorldForge remains independently installable. Use PlatformWorkspaceBridge (reflection-only) so provider code does not link against Platform:

```java
PlatformWorkspaceBridge.publish(
    "wf_people", characterId, "CHARACTER", displayName, summary
);

// Additional typed inspector fields for the selected entity.
PlatformWorkspaceBridge.registerInspector("wf_people", entityId -> List.of(
    new String[]{"Identity", "Name", displayNameFor(entityId)},
    new String[]{"Household", "House", houseFor(entityId)}
));

// Context menu action.
PlatformWorkspaceBridge.registerAction(
    "wf_people.open_family", "Open family", "wf_people",
    () -> openFamilyForCurrentSelection()
);

// Open the selected search result in the provider's own screen/map.
PlatformWorkspaceBridge.registerEntityOpener(
    "wf_people", entityId -> focusCharacter(entityId)
);

// Map layer; Object is the optional GuiGraphics instance passed by Platform.
PlatformWorkspaceBridge.registerMapLayer(
    "wf_realms.borders", "Political borders", "wf_realms", 10,
    true, 0.85f,
    (graphics, x, y, width, height, opacity) ->
        renderPoliticalBorders((GuiGraphics) graphics, x, y, width, height, opacity)
);
```

Selection publication automatically indexes the selected entity for search and writes a selection event. To make the full world searchable (not just entities the player has already selected), each provider must index its own visible/known entities through WorkspaceRuntime.index or an equivalent bridge method. Providers must register entity openers for navigation to the actual owning view.

Providers can also register shortcuts directly through WorkspaceRuntime.registerShortcut and publish events through WorkspaceRuntime.publish.

## Honest remaining work / acceptance gates

This PR is a foundation plus a first live-shell integration pass. It does not claim all game systems are now fully integrated.

1. Existing provider modules have not yet been individually migrated to register every inspector, contextual action, entity opener, map layer and shortcut. The WorldForge reflection bridge now exposes the hooks; provider-specific calls still need to be added where their actual domain actions/data are implemented.
2. Global search currently contains entities that publish selection context. Full-world search needs provider-side indexing and server-aware paging/permission checks.
3. The UI primitives are a shared source for new work, but legacy Atlas/WorldForge screens still contain mirrored drawing code. Full component-library adoption requires migrating those screens without breaking independent mod installation.
4. Capability declarations still include a built-in catalogue. Runtime registration is supported, but the existing modules have not all moved to self-registration.
5. Some routes intentionally issue existing provider commands when no embedded screen/factory exists. They must be replaced only when a real provider view or server-driven panel is available; no placeholder should be presented as a finished screen.
6. Visual QA is still required in Minecraft at 16:9, ultrawide, 1366x768 and low-resolution GUI scales, including mouse/keyboard focus and provider screen input clipping.
7. Save/load needs in-game verification across multiple named profiles and after module removal/reinstallation. The persistence store has unit tests, but no user-facing profile picker is added in this pass.

## Tests added

- Dock tree: split geometry, minimum sizes, nested layouts, tab activation, drag/drop primitives, splitter resize, removal/collapse and invalid IDs/weights.
- Runtime services: search ranking, provider openers, inspector/action scoping, event journal bounds and listener delivery, shortcut conflicts and provider unload.
- Profiles: named profile save/load/list/delete and corrupt-profile handling.
- Map layers: provider registration, visibility, opacity and unload.

The PR remains a draft until the Windows build, all unit tests, and in-game acceptance checks pass.

## Expanded acceptance backlog supplied for the MAQAN world platform

The following requirements are explicitly **not certified as complete by PR #14**. They are cross-stage dependencies and must remain visible in the release gate. Workspace infrastructure must not be confused with completion of the game-domain systems that consume it.

### Earlier stages that must be closed or integrated

| Stage | Required outcome | Current verified status in this PR |
|---|---|---|
| VII — World Character Registry | One world-level identity/character registry shared by every map, settlement, court, army and simulation; stable IDs, lifecycle, ownership/location references, deduplication and cross-map lookup | Not implemented here. Current workspace search index is only a presentation/search index, not the authoritative character registry |
| VIII — Population and Court AI | Executable AI for court NPCs and population outside settlements, including decision loops, needs/goals, schedules, movement, task assignment and persistence | Not implemented here. Provider bridge does not supply an AI runtime |
| IX — Economy and Logistics | Global price formation, market connectivity, transport/logistics, stockpiles, supply/demand, financial ledger recovery/reconciliation and auditable transactions | Not implemented here. Workspace journal is an event display, not the financial ledger |
| X — Live Spatial Platform | Live authoritative spatial state and synchronization between world simulation, map projections, selection, layers and clients; eliminate raster-snapshot/manual-export dependency | Not implemented here. Map layer registry is a rendering extension point, not spatial synchronization |
| XI — RTS and Hero Progression | Full combat abilities panel, hero progression branches, and one task graph shared by workers, specialists, soldiers and heroes; order queues and execution feedback | Not implemented here. Workspace shortcuts/actions do not constitute the RTS task graph or ability system |

### Stage XII — required substage gates

| Substage | Acceptance criteria | Status from PR #14 |
|---|---|---|
| XII-A — Docking Engine | Nested split tree, tab groups, drag/drop docking, resize, collapse/restore, stable panel identity, keyboard and pointer behavior | Core tree and shell interaction implemented; in-game edge cases and full visual QA remain |
| XII-B — Workspaces and profiles | Named layouts, save/load/delete/rename, active profile, migration/versioning, recovery from corrupt data, persistence across restart and provider removal | Store and save/load API implemented; visible profile manager and full in-game lifecycle QA remain |
| XII-C — Provider registry and component library | Dynamic provider lifecycle, capability discovery, versioned component catalog, shared reusable controls, no duplicated legacy UI primitives | Runtime hooks exist; provider self-registration and migration of legacy screens remain |
| XII-D — Universal inspector | One inspector contract for all domain entities, common identity/location/ownership/status sections, provider-specific sections, live refresh and safe empty/error states | Generic provider inspector exists; domain-wide adapters and shared entity schema remain |
| XII-E — Global search and context actions | Search across all registered world entities with paging/filtering/permissions; open result in owner view; context actions validated against current selection and authority | Local in-memory index, provider opener and action registry exist; full-world indexing, paging and provider migration remain |
| XII-F — Notifications, events and history | Unified notification center, severity/category filters, read/unread state, durable event history, subscriptions and domain-specific audit trails | Bounded in-memory event journal and shell rendering exist; durable history, notification center and audit integration remain |
| XII-G — Maps, layers, selection and shortcuts | Live map data source, composable layers, shared selection across all views, stable coordinate transforms, shortcut scopes/conflict UI and provider registration | Layer registry, selection context and shortcut registry exist; live spatial synchronization and provider-wide integration remain |
| XII-H — Responsive, accessibility and polish | Multiple resolutions/aspect ratios/GUI scales, minimum sizes, keyboard-only navigation, focus order, screen-reader labels where supported, contrast, tooltip and input clipping QA | Shared scaling/drawing primitives exist; systematic accessibility and visual acceptance testing remain |

### Universal inspector entity contract

The inspector is one shared shell component, not a separate screen per entity type. It must resolve a stable entity reference (providerId, entityId, canonical entityKind, worldId) and compose common fields with provider-owned domain sections.

Required entity coverage:

- Characters and families.
- Workers, soldiers and heroes.
- Buildings and settlements.
- Armies and squads.
- States, titles and territories.
- Planets, stars and other space objects.
- Resources, production chains and trade/market records.

Minimum common inspector behavior:

1. Stable identity and canonical kind; display name and owning provider.
2. Current world/location and map focus action where spatially meaningful.
3. Ownership, faction/house/state/settlement relations where applicable.
4. Lifecycle/status and current activity/order where applicable.
5. Provider-contributed domain sections with typed values, not only preformatted strings.
6. Context actions gated by entity kind, current selection, permissions and simulation authority.
7. Cross-view selection synchronization and a provider opener that focuses the actual entity.
8. Graceful behavior when a provider is absent, an entity unloads, or an inspector contribution fails.

The existing SearchEntry and InspectorField are presentation contracts only. They must not become a second source of truth for world entities. The authoritative identity and simulation state stay with the owning world/domain services; Platform consumes stable references and typed snapshots.

### Release rule

Do not mark Stage XII complete merely because the shell builds or its unit tests pass. Completion requires:
- all XII-A through XII-H gates above;
- real provider adapters for every listed inspector entity family;
- cross-stage VII-XI integration where those systems supply registry, AI, economy, spatial and RTS data;
- automated tests plus in-game acceptance on representative resolutions and GUI scales;
- no dead-end/placeholder routes and no duplicate authoritative registries or ledgers.

This backlog supersedes any narrower interpretation of “all game systems available in one interface.” PR #14 is an infrastructure increment, not evidence that Stages VII-XI or every XII substage are complete.


## Follow-up implementation pass — workspace manager and notifications

The shell now includes two visible first-pass overlays in addition to the earlier profile-store and journal APIs:

- Workspace profile manager: open from the top bar, list saved profiles, restore a selected layout, save the active layout, create a uniquely named profile from the current layout, and delete the active non-default profile.
- Notification/event center: open from the top bar and inspect the latest in-memory workspace events with severity, source, category and world context.

These are useful UI surfaces, but they are not yet the full acceptance implementation:
- Profile rename, user-entered naming, scrolling/paging for large profile lists, duplicate/overwrite confirmation and lifecycle tests across restart/provider removal remain.
- Notification read/unread state, filters, durable history, acknowledgement/clear controls and event retention policy remain. The underlying journal is still bounded in-memory history.
- The manager currently uses generated profile names for the create action; it does not yet expose a text-entry dialog.

The follow-up commit also removes a Java lambda capture issue in unique profile-name generation.

## Current explicit completion boundary

The seven follow-up tasks should now be read as follows:

1. Provider integration — the generic bridge exists; per-module real registrations are still outstanding.
2. Full-world search — the shell searches the local runtime index; providers must index their full authorized entity catalogs and provide paging/update/removal.
3. Component library — shared drawing primitives and descriptors exist; legacy Atlas/WorldForge screens have not all migrated to a versioned shared widget/component set.
4. Workspace manager — a basic visible manager is now present; full rename/input/large-list/confirmation and lifecycle QA remain.
5. Notification center — a visible event-history overlay is now present; durable notification-center behavior remains outstanding.
6. Responsive behavior — only shared scaling primitives and layout minimums exist; no completed in-game matrix of resolutions, aspect ratios, GUI scales, keyboard navigation and accessibility has been certified.
7. Embedded routes — some provider screens mount inside the shell, while other capabilities still invoke provider-owned commands because no embeddable view factory is registered. Those routes are functional provider commands, not proof of a unified embedded workspace. Each remaining route needs an actual embedded view or an explicitly documented non-UI action; never substitute a decorative preview for missing domain functionality.
