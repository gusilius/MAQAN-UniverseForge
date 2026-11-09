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