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

- At desktop sizes, preserve the supplied composition and let the user's dock tree allocate panels.
- At compact viewports, keep the world/map as the full underlying canvas and expose navigation, inspector, tools and timeline as one-at-a-time slide-over drawers.
- Keep hit regions aligned with rendered elements using the same viewport transform.
- Maintain aspect ratio for texture frames; do not stretch decorative artwork to arbitrary screen dimensions.
- Clamp all windows and drawers to the available viewport. No panel may be positioned beyond the screen or overlap another docked panel.
- Respect Minecraft GUI scale and actual scaled screen dimensions, not only physical framebuffer pixels.
- Keyboard access, tooltips and focus state remain available for icon-only controls.

## Current implementation slice

- `MaqanUiPackLayout` loads and validates `assets/maqan/gui/layout.json` at runtime; it checks the 1368×760 reference viewport, all four mandatory themes and all eight frame rectangles per theme.
- `MaqanUiPackRenderer` consumes those runtime rectangles instead of maintaining a second hardcoded geometry table. Drawing and hit-testing share the same letterboxed viewport transform.
- The renderer validates all ten unique texture files for each of the four themes before activating the concept frame.
- When all pack textures are present, the concept frame replaces the generic Platform chrome instead of drawing duplicate rails and panels over it.
- The left/right frame positions use the canonical blank-window PNGs. Baked state/diplomacy sample artwork is not used as live window content.
- The existing responsive layout calculator and compact drawers remain available as fallback when the manifest or texture pack is unavailable.
- The four themes are switchable with F7 and the selected skin is persisted in the user's local workspace preferences. This changes artwork only; it does not replace the active provider or selection state.
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
