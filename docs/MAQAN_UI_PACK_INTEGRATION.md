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
