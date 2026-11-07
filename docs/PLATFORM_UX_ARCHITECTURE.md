# MAQAN Platform UX Architecture

## 1. Product model

MAQAN is a desktop-like interactive platform inside Minecraft, not a single oversized screen. The shell hosts independent workspaces and tool windows. Atlas and WorldForge remain separate mods; the shell can operate with either one installed.

## 2. Persistent shell

### Top system bar
- Workspace tabs: Atlas, World, Economy, Politics, Research, Army, Administration.
- Global simulation clock: date, time, pause/play, speed and synchronization state.
- Notifications, connection/session state, quick settings, window controls.
- Settings opens a compact anchored popover, not a full-screen settings page.

Tabs are capability-aware: unavailable workspaces are hidden or shown as unavailable, never represented by nonfunctional controls. Each workspace remembers its own layout, selected object, map layers, zoom and open tools.

### Bottom Dock
Persistent icon dock for high-level destinations: Navigation, Research, Diplomacy, Economy, Army, Management, World Map, Plans/Journal. Dock entries open or focus workspaces/tools; they do not replace the top-level workspace model.

### Sidebars
Left and right sidebars independently retract, resize and remember state.
- Left: object explorer, hierarchy, search, filters, favorites, map layers and navigation.
- Right: contextual inspector for the current selection, related objects and actions.
Both can be collapsed to slim edge rails. Neither should permanently consume the map viewport.

## 3. Window manager

Every tool window has stable identity, title, icon, minimum/maximum size, z-order, focus state and serializable layout state. Support:
- drag by title bar; resize from edges/corners;
- minimize, maximize, close and restore;
- snap/dock zones and split layouts;
- tab groups;
- pin/always-on-top;
- duplicate views where meaningful;
- workspace-specific layout save/restore and reset;
- keyboard focus traversal and safe bounds.

Avoid uncontrolled overlap: snap previews, bring-to-front behavior, minimum viewport constraints, overflow recovery and a reset-layout command are required. Windows must not cover essential shell controls. Tool state and window geometry are separate so closing a view does not delete simulation data.

## 4. Contextual inspection

Selection is global context, but inspectors are type-specific. A planet, kingdom, settlement, army and dynasty must not share a generic property sheet. Each inspector declares supported tabs, actions, related entities and data provenance. Missing data is shown as unknown/unavailable, never invented.

## 5. Time and simulation controls

The global clock is the authoritative presentation of simulation time. Pause and speed controls must dispatch to the simulation authority; they must not merely animate the clock label. Distinguish world simulation time from real local time. Show paused, catching-up, offline and multiplayer-authoritative states.

## 6. Maps and layers

Map view is a first-class workspace surface. Sidebars and floating windows supplement it rather than replacing it. Layers are composable and independently toggleable. Selected entities, ownership, borders, routes, terrain, resources, population, weather, armies and orbital paths have explicit legends and source metadata.

## 7. Persistence and accessibility

Persist per-player layouts, workspace selection, sidebar widths, dock preferences, window geometry and map state. Provide keyboard navigation, scalable text, contrast options, reduced motion and localization. Layout migration must be versioned.

## 8. Implementation sequence

1. Shell layout and workspace registry.
2. Window manager and persisted layout model.
3. Sidebars, Dock, clock and settings popover.
4. Object selection/context inspector contracts.
5. Atlas and WorldForge workspaces.
6. Cross-mod integration and multiplayer authority.
7. Polish, accessibility, telemetry-free diagnostics and regression tests.

## 9. Quality gates

- No placeholder actions in released UI.
- No fake orbital or simulation values.
- Every panel has a defined data owner and empty/loading/error states.
- Workspace switching does not discard state.
- Installing only Atlas or only WorldForge remains supported.
