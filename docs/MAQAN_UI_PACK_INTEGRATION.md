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

This branch adds a reusable responsive layout calculator and unit coverage, then wires compact drawers into `PlatformShellScreen`. The existing desktop dock tree and provider embedding contracts remain intact.

**Asset status:** the source ZIP has been inspected, but the GitHub connector available to this session accepts UTF-8 text blobs/files only and cannot upload the ZIP's binary PNGs. Therefore this branch does not yet contain the 40 PNG assets or the source `layout.json`; the manifest records their exact required names and placement. The visual skin is not considered integrated until those binaries are committed and the native renderer consumes them.

## Acceptance gates

1. All 40 PNG files exist at the required paths and load in all four themes.
2. Theme switching changes only artwork/style, not provider state or workspace identity.
3. The blank windows display real provider-backed data and actions.
4. No baked sample values are shown as live resource counts.
5. Verify 1920×1080, 1366×768, 1024×768, 800×600, 640×480 and ultrawide layouts at multiple Minecraft GUI scales.
6. Verify drag, resize, dock, tab, minimize, restore, focus, tooltips, mouse and keyboard behavior.
7. Run Platform unit tests and the Windows x64 Platform build before calling this integration complete.
