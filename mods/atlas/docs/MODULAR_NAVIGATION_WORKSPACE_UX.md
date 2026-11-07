# MAQAN Modular Navigation Workspace UX

Status: implementation contract for the modular workspace feature.

## Non-negotiable preservation
- Keep the existing Cosmic Horizons cube-body models and their original textures/rendering.
- Keep the current catalogue, hierarchy, map modes, search, filters, routes, waypoints, labels, layers, orbit controls, and context actions.
- Add this workspace as a presentation and interaction layer; do not replace the data model or remove existing capabilities.

## Workspace composition
- Three independently visible panels: Navigation, Map, Object Profile.
- All panels can be shown/hidden independently, reordered by drag-and-drop, resized, and restored to defaults.
- The map receives the remaining available space. Panel contents must be clipped to their own bounds.
- The Object Profile is a real third workspace panel, not a modal replacement for the map.
- Support simultaneous views: map + navigation + profile, map + profile, map + navigation, or map alone.

## Context actions and route flow
- Right-clicking a celestial/system/sector/galaxy object opens a compact anchored context menu.
- "Build route" opens a compact floating route planner prefilled with the selected object as destination.
- Route planning must be distinct from route execution. Show the selected transport mode and verified route legs before enabling execution.
- Transport options are selected from actually available/verified links and travel capabilities; never claim a jump/warp/link is available from a manual toggle alone.
- Remove the permanent bottom TRAVEL strip from the primary workspace. Keep direct travel functionality accessible through the compact route workflow.

## View-specific tools
- Replace the undifferentiated Tools pile with small categorized popovers/sections.
- Universe, galaxy, sector, star-system, and planet views each expose their own relevant controls.
- Global workspace controls (panel visibility/order, appearance, input behavior) are separate from view-specific controls.
- Floating settings windows have a draggable title bar, bounded movement, compact dimensions, and do not block the map outside their own rectangle.

## Object Profile
- Profile tabs depend on selected object type.
- Planet profile uses separate tabs for Overview, Surface, Environment/Climate, Resources, Biomes/Life, Politics, and Source where data exists.
- System profile has its own Overview, Bodies/Orbits, Travel, Environment, and Source sections.
- Galaxy/sector profiles expose their own Overview, Systems/Territories, Routes, and Source sections.
- Do not put all fields in one long scroll view. Scrolling is local to the active tab only.
- Missing data must be shown as unavailable/unknown, not fabricated.

## Visual and interaction quality
- Minimal chrome, large map canvas, restrained borders, consistent spacing and typography.
- Buttons have unique actions, non-overlapping hitboxes, clear active/disabled/hover states.
- Popovers are modal only within their own bounds; clicks outside dismiss them without leaking to controls underneath.
- Mouse wheel zoom is pointer-anchored; map panning, camera orbit, and panel dragging have distinct gestures.
- Every map level supports wide zoom range and useful LOD down to individual systems and bodies.
- Labels and body models must remain clipped to their panel and avoid obscuring critical text.

## Acceptance checks
- Build Forge 1.20.1 JAR and verify it is a valid mod artifact.
- Test all workspace panel combinations, reorder/resize/restore, and small GUI scales.
- Test all five object types (universe, galaxy, sector, system, planet) and confirm the right profile tabs/tool sections change correctly.
- Test right-click route creation, planning, transport selection, server validation, and successful arrival; invalid/unavailable routes must fail safely with a clear reason.
- Test map zoom/pan/LOD, orbit animation, and cube-body rendering in the actual Minecraft client.
- CI success alone is not a substitute for the in-game checks above.
