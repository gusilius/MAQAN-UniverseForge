# MAQAN UI Pack Integration

## Product decision

The four supplied skins are mandatory: **royal**, **iron**, **grim**, and **elf**. They are themes of the one MAQAN Platform workspace, not separate screens or separate mod interfaces. Additional themes may be added later without changing provider integrations.

The uploaded pack defines a 1368×760 reference viewport. Its PNGs are authored at 2× resolution. The layout metadata/hotspots remain in reference-space coordinates and must be transformed through one shared viewport transform.

## Runtime integration contract

- Install the pack textures under `platform/src/main/resources/assets/maqan/textures/gui/{royal,iron,grim,elf}/`.
- Keep the exact ten texture names in `shared/contracts/ui-skins.json`.
- Render the existing native Minecraft workspace; do not introduce MCEF or replace the Platform shell with an HTML browser.
- Preserve the pack's composition and visual hierarchy: top strip, side columns, minimap, RTS console, corner ornament and contextual windows.
- Use the blank left/right windows for live content. The pre-rendered state/diplomacy samples are reference artwork only.
- Never expose the baked resource numbers in `top_bar.png` as current game resources. Live values must come from the authoritative provider.
- The four themes share the same geometry and interaction model. Only their artwork/skin tokens differ.
- The shell remains capability-driven: Atlas, WorldForge and addon views continue to register into the same workspace and retain domain ownership.

## Responsive behavior

The 1368×760 composition is the design reference, not a fixed pixel canvas.

- At desktop sizes, preserve the original per-theme composition at the 1368×760 reference size; on larger and smaller windows, anchor the side columns and contextual windows to their corresponding screen edges.
- The top bar fills the available width while retaining its source height ratio. Minimap, RTS console and side windows preserve their individual theme-specific proportions.
- At compact viewports (the shared 840 px width / 560 px height breakpoint), omit the desktop side-window frames and retain the minimap plus RTS console in a responsive bottom dock. Navigation, inspector, tools and timeline use the existing one-at-a-time native drawers.
- All computed frame rectangles are clamped to the scaled Minecraft GUI viewport. The compact map and command dock must not extend beyond the screen or overlap one another.
- Keep hit regions aligned with rendered elements using the same geometry source when interaction hotspots are added.
- Respect Minecraft GUI scale and actual scaled screen dimensions, not only physical framebuffer pixels.
- Keyboard access, tooltips and focus state remain available for icon-only controls.

## Current implementation slice

- `MaqanUiPackLayout` loads and validates `assets/maqan/gui/layout.json` at runtime; it checks the 1368×760 reference viewport, all four mandatory themes and all eight frame rectangles per theme.
- `MaqanUiPackRenderer` consumes runtime geometry and `MaqanUiPackAdaptiveLayout` computes responsive placements per theme. At the reference viewport the source composition is preserved; other aspect ratios reflow instead of uniformly shrinking the entire frame.
- The renderer validates all ten unique texture files for each of the four themes before activating the concept frame.
- When all pack textures are present, the concept frame replaces the generic Platform chrome instead of drawing duplicate rails and panels over it.
- The left/right frame positions use the canonical blank-window PNGs. Baked state/diplomacy sample artwork is not used as live window content.
- The shared 840×560 compact breakpoint is used by both the skin renderer and workspace drawer layout. Compact mode keeps minimap and RTS console visible, while replacing the side windows with native drawers.
- The four themes are switchable with F7 or the native workspace settings pop-out; the selected skin is persisted in the user's local workspace preferences. Search, context, settings, workspace-profile and notification overlays remain rendered above the concept artwork. Skin changes affect artwork only; they do not replace the active provider or selection state.
- Adaptive layout unit tests cover desktop composition, compact and portrait layouts, tiny windows, screen bounds and artwork aspect ratios. Tests have been added but not executed yet.
- Minimap data, real camera-mode switching, map-mode switching, live resource labels and provider-backed contextual content remain open integration tasks.

The original concept is available as a self-contained HTML file with all 40 image layers embedded as WebP data URIs and the per-theme 1368×760 coordinates embedded in its `SK` and `MT` objects. It is an asset source only; it is not shipped or executed in Minecraft.

Import the source file into native PNG resources on Windows:

```powershell
python -m pip install Pillow
python tools/import_maqan_ui_concept.py --concept-html "C:\\path\\to\\MAQAN — интерфейс из концептов.html" --repo-root .
```

The importer validates all four themes and all ten image layers per theme, decodes the embedded WebP images to optimized RGBA PNGs, and writes `assets/maqan/gui/layout.json` with the original per-theme geometry. It does not copy or embed the HTML prototype.

**Asset status:** all 40 PNG files are committed under `platform/src/main/resources/assets/maqan/textures/gui/{royal,iron,grim,elf}/` (10 per theme, approximately 8.9 MB total). The runtime manifest and renderer are on the active integration branch.

## Acceptance gates

1. All 40 PNG files exist at the required paths and load in all four themes.
2. Theme switching changes only artwork/style, not provider state or workspace identity.
3. The blank windows display real provider-backed data and actions.
4. No baked sample values are shown as live resource counts.
5. Verify 1920×1080, 1366×768, 1024×768, 800×600, 640×480 and ultrawide layouts at multiple Minecraft GUI scales.
6. Verify drag, resize, dock, tab, minimize, restore, focus, tooltips, mouse and keyboard behavior.
7. Run Platform unit tests and the Windows x64 Platform build before calling this integration complete.

### Compact workspace and persistent dock integration

- The compact workspace receives a reserved-bottom measurement from the active skin layout. The content canvas and every native drawer stop above the minimap/command dock; they no longer render underneath persistent controls.
- The concept top bar is decorative artwork with native shell controls layered over it. Profile tabs, workspace profiles, notifications and close actions remain visible and retain the same hit regions.
- The compact navigation rail calculates how many drawer shortcuts fit above its settings control. Short viewports hide shortcuts that cannot fit rather than allowing them to overlap the settings button or bottom dock.
- The top bar keeps a minimum usable control height on narrow screens; the original reference layout remains unchanged at the supplied 1368×760 geometry.

### Provider content composition and desktop map viewport

- Desktop skin geometry exposes one central content viewport between the imported left/right blank frames and above the minimap/RTS dock. The preview and registered map overlays use this viewport; the desktop provider view retains its full host dimensions so its own catalogue and dossier hit regions are not cropped by the generic dock tree.
- The imported skin is drawn before provider content. Atlas' embedded renderer suppresses its standalone background and opaque panel surfaces, while retaining its catalogue, object dossier, map geometry, selection and input handling. This lets provider-owned content appear over the MAQAN concept frames without replacing provider data or hit-testing.
- In compact mode, Atlas hides its own side columns and yields that space to MAQAN's native drawers. Its map is clipped to the workspace canvas above the top bar and persistent minimap/command dock.
- Profile changes no longer clear the shared entity selection. Existing provider screen instances remain cached, preserving provider-owned camera/view state when switching away and back. Camera modes and map modes remain separate concepts; no fake RTS camera action is exposed until a real game-camera controller is connected.
- Tests cover the desktop central viewport bounds and verify that compact layouts do not use desktop side-window geometry.

### WorldForge political map in the shared skin

- The existing WorldForge `MapScreen` now exposes the optional embedded-host hook used by Platform. It keeps its server-owned political map, province selection, map modes, editing actions and side panel; the shared shell supplies the background and frame composition.
- The political map fits to the adaptive central viewport instead of the entire desktop. Its map texture, labels and markers are clipped to that viewport; its scale, coordinate plate and map-mode hotbar are positioned relative to the same bounds.
- The overview is rendered as live map imagery inside the imported minimap frame, including the visible-camera rectangle and click/drag recentering. The RTS command dock and shell navigation are excluded from map selection, panning and zoom.
- In compact mode, the political map yields the side columns to the MAQAN drawers and keeps its map canvas above the persistent bottom dock.

### RTS and settlement HUD in the shared skin

- The embedded RTS HUD declares itself as a transparent world overlay, so the shared shell no longer paints an opaque background over the live Minecraft world.
- Its settlement minimap is rendered from live buildings/workers into the imported minimap frame. The command card is hosted by the imported RTS console frame; selection/unit details occupy the left window and settlement resources, population, approval and alerts occupy the right window.
- Existing RTS selection, command-card actions, placement, priority editing, worker/building orders and provider selection publication remain owned by the RTS HUD. The shell only supplies the shared visual surfaces and viewport boundaries.
- Compact layouts keep the minimap and command card in the persistent bottom dock; the selection summary moves into the available map canvas rather than creating another side column.

### Independent camera and map modes

- `WorkspaceViewState` stores camera and map mode independently. Camera modes are `FIRST_PERSON`, `FREE` and `RTS`; map modes are `LOCAL_MINIMAP`, `WORLD_MAP` and `POLITICAL_MAP`. Switching one axis never resets the other or the provider-owned selection.
- `MaqanCameraRig` is pure camera geometry: camera-relative pan, yaw/pitch orbit, bounded zoom and a leash around the player to avoid moving beyond client-loaded terrain.
- `MaqanCameraController` creates an unspawned client-only camera anchor. It does not move the player, send movement packets, add entities to the world, or alter server simulation. Leaving a world restores the previous camera and resets the mode to first person.
- In the shared shell, F8 and the camera icon cycle camera modes; WASD/arrows pan, Shift accelerates, Q/E rotate yaw, R/F adjust pitch, middle-mouse drag orbits, and the mouse wheel zooms while over the central workspace viewport. F9 and the map icon cycle the independent map-mode state.
- The current slice establishes the actual camera anchor and independent mode state. Map-mode routing to concrete Atlas/WorldForge provider views, edge scrolling, and direct world-click selection outside the provider HUD remain subsequent integration work; they are not represented as complete by this commit.
