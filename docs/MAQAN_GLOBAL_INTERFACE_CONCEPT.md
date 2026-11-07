# MAQAN Global Interface — Canonical Product & UX Concept

**Status:** Canonical direction / implementation contract  
**Scope:** MAQAN Platform, Atlas, WorldForge, every WorldForge addon, future modules, RTS and tactical modes.  
**Target baseline:** Minecraft 1.20.1 Forge for the current integrated line. Keep module-specific loader/build targets independent where required.

## 1. Product definition

MAQAN is a persistent, modular, futuristic command environment for exploring and governing a connected universe. It is not a collection of Minecraft menus and not a skin applied independently to each mod. The player works inside one coherent platform; modules contribute capabilities, data and views to that platform.

The interface language is restrained, cinematic and spatial: deep near-black/navy surfaces, subtle separators, restrained cyan focus/selection, semantic status colors, crisp typography, quiet motion and large uninterrupted work surfaces. The map, simulation and selected world object are the visual focus. Avoid ornamental frames, bevels, gold trim, excessive stars, noisy gradients and permanently visible text-button toolbars.

## 2. One entry point, contextual navigation

- A single MAQAN launcher/command button opens the global navigation surface.
- Navigation is organized into tabs and nested categories, not dozens of independent top-level buttons.
- The same navigation framework is used by all modules and all play modes.
- Tabs are capability-driven: a module registers its capabilities; the platform displays only capabilities actually provided by installed and enabled modules.
- No installed provider means no tab, no dead button and no fake placeholder screen.
- A provider can contribute one or more routes/views to an existing domain (e.g. WorldForge addons contribute Settlements, Army, Trade, Court, War, Quests).
- A module may run independently without Platform. Platform integration is optional and must not become a hard dependency.
- Cross-module integration uses versioned shared contracts. Each module remains authoritative for its own domain data, persistence, lifecycle and saves.

## 3. Domain map

The global navigation groups capabilities into stable domains. This is a semantic map, not a promise that every item is always visible.

| Domain | Typical views | Example provider |
|---|---|---|
| Cosmos | Universe, galaxies, sectors, systems, stars, orbits, bodies, moons, routes, travel | Atlas |
| Worlds | Planet atlas, surface maps, biomes, regions, points of interest, world generation | Atlas / WorldForge |
| Politics | Realms, borders, territories, diplomacy, relations, laws, titles, vassals | WorldForge Realms / Relations / Court |
| Settlements | Cities, villages, colonies, buildings, population, production | WorldForge Settlements / People |
| Army | Armies, troops, formations, orders, wars, tactical operations | WorldForge Army / Troops / Orders / War / RTS |
| Society | Characters, families, culture, origins, reputation, dialogue, quests | WorldForge People / Origins / Forms / Reputation / Dialogue / Quests |
| Economy | Resources, markets, trade routes, production, treasury | WorldForge Trade / Progress |
| Creation & Tools | Character start, genesis, world tools, editors, diagnostics | WorldForge Genesis / Platform tools |
| Settings | Layouts, controls, accessibility, module status, data sources | Platform + providers |

The domain stays stable while the actual views within it are registered dynamically.

## 4. Workspace and panel model

The workspace is a canvas, not a fixed menu layout. A central map/scene remains visible while panels can be rearranged around it.

Panel types:
- **Dock:** attached to left, right, top or bottom edge; collapsible.
- **Floating:** independent movable/resizable window.
- **Inspector:** contextual details for the selected entity; can be pinned.
- **Split view:** two or more work surfaces side by side.
- **Overlay:** transient contextual tools which do not replace the workspace.
- **Minimized rail item:** hidden panel represented by an icon.
- **Pinned panel:** cannot be dismissed by incidental interaction.

Every panel has stable identity, owner module, panel type, visibility, dock/float state, bounds, z-order, pin state and optional tab group. Multiple instances of a panel type are allowed when its provider supports them.

User operations: drag, resize, dock, undock, snap, tab-group, reorder, minimize, restore, pin, hide and close. Closing is explicit; a click on a panel, map or tab must never dismiss the workspace or an unrelated panel. Clicking outside a contextual popup may dismiss that popup only, never the underlying workspace. Escape closes the topmost transient popup first; it does not close the whole platform unless the user explicitly exits.

Layouts are user-configurable and persist per profile. Initial profiles: **Exploration**, **Administration**, **Diplomacy**, **RTS**, **Tactical**, **World Editing**. Users can create, duplicate, rename, reset and export layouts. Module removal must gracefully prune unavailable panel instances without deleting unrelated layout state.

## 5. Side rails and controls

- Left and right rails are independently collapsible; top and bottom docks are optional.
- Rails are icon-first. Labels appear on hover, focus, expanded navigation or accessibility mode.
- Toolbars remain small; advanced actions live in contextual menus, command palette, inspector tabs or module-specific tool drawers.
- A selected object opens/updates the inspector rather than navigating away.
- The map/scene remains interactive when panels are open; panels occupy reserved layout space or float with deliberate z-order.
- Panel headers contain only a drag handle/title and minimal icon actions (pin, dock, minimize, close). No large button rows.

## 6. One visual language, multiple modes

Cosmos, world management, diplomacy, city management, RTS and tactical modes are not separate UI products. They are workspace profiles and context-specific view compositions over the same panel manager, navigation registry, theme tokens, selection model, command system and input rules.

RTS profile may rearrange the workspace into tactical map, command palette, unit selection, minimap, orders and event feed. It may expose contextual hotkeys and icon grids, but it retains the same surfaces, typography, spacing, selection feedback, panel behavior and navigation entry point. Switching modes changes layout and available capabilities, not the platform identity.

## 7. Selection, context and interaction

- One global selection context carries selected entity ID, entity type, owning module, world/dimension and source revision.
- Selection is non-destructive: clicking a planet, realm, settlement or army updates the inspector and linked views; it does not close other panels.
- Context menus are anchored to the selected entity and contain only valid actions registered by installed providers.
- Routes and travel actions are supplied by Atlas; political ownership and settlement simulation remain WorldForge-owned.
- Cross-links are explicit and contract-based. No duplicate ownership of entities or simulation state.
- Tooltips are concise and appear on hover/focus; icons must have accessible labels.

## 8. Capability registry and contracts

Each module declares a stable module ID, semantic version, capability IDs, navigation contributions, panel factories, optional commands, data adapters and compatibility range. Registration occurs during module discovery. The platform checks that the provider is loaded, enabled and compatible before exposing a capability.

Capability IDs are namespaced and stable (e.g. `maqan.atlas.cosmos`, `worldforge.realms.politics`, `worldforge.rts.command`). The registry must support missing/failed providers, duplicate registration detection, dependency ordering, version negotiation and safe unload. The platform must never fabricate data to fill missing capabilities.

Shared contracts describe references and events, not domain ownership. Example: Atlas provides a stable celestial-body reference; WorldForge may attach realm/settlement ownership to that reference. Atlas does not own the realm; WorldForge does not own orbital coordinates.

## 9. Persistence and accessibility

Persist panel layouts, dimensions, positions, z-order, dock side, pin/minimize state, active domain/view, selected profile, shortcuts and user icon/label preferences. Keep layout schema versioned and migrate forward safely. Provide defaults when a module or panel is missing.

Support keyboard navigation, focus indicators, screen-reader names for icon-only controls, scalable UI, reduced motion and high-contrast mode. Mouse interaction must never be the only way to reach a command.

## 10. Non-negotiable acceptance criteria

1. One MAQAN entry point; no forest of always-visible text buttons.
2. Only installed and compatible module capabilities appear.
3. The same panel manager and visual language are used in Atlas, WorldForge and RTS.
4. Panels can be moved, resized, docked, grouped, minimized, pinned and restored.
5. Clicking a panel or map never closes the workspace or another panel.
6. The central map/scene remains usable with side panels open.
7. Module absence removes its navigation contributions without breaking other modules.
8. Atlas keeps its existing square/cuboid celestial models, orbital simulation, maps, routes and travel.
9. WorldForge keeps ownership of civilizations, territories, settlements, population, politics, economy and military simulation.
10. No external map mods or fake data placeholders are introduced.
11. Every meaningful change must pass Windows x64 build and tests before being called complete.
12. The platform shell is optional; Atlas and WorldForge remain independently installable.

## 11. Implementation sequence

**Foundation:** theme tokens, capability registry, navigation registry, shared selection context, panel descriptors and layout persistence.

**Shell:** one launcher, dynamic domain tabs, icon-only rails, command palette, panel manager and layout profiles.

**Atlas:** adapt existing navigator as the Cosmos/Worlds workspace; preserve renderers and data flows; replace fixed layout assumptions with panel descriptors; keep cube planets.

**WorldForge:** route existing map, political, settlement, army, economy and society screens through the shared workspace and panel model; addon capabilities register themselves.

**RTS/Tactical:** use the same workspace and panel system with an RTS profile, command palette and contextual unit actions.

**Verification:** independent module builds, combined installation smoke tests, missing-module tests, layout persistence tests, pointer interaction tests and regression tests for existing navigation/travel/simulation.
