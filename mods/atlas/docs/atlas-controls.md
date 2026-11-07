# Navigator atlas controls

## Map navigation

- Left-drag empty map space to pan the current map.
- Mouse wheel over the map to zoom toward the cursor position.
- **Focus** centers the selected system or body.
- **Center View** resets pan, zoom, and the 3D camera orientation.
- Double-click a system marker to open that system.
- Right-click a body marker to open its contextual actions.
- In 3D system view, right-drag changes the camera orientation; left-drag empty map space pans.
- In the planet explorer, drag the rendered body to rotate it.
- Drag the object preview in the information panel to rotate that preview independently.

## Atlas scope

The atlas scope selector cycles between the active star system, systems sharing the active galaxy metadata, systems sharing the active universe metadata, and a **Galaxies** overview for the active universe. In Galaxies view, each galaxy is one selectable spiral glyph; double-click or select and press **Open** to drill into its systems. **Back** returns to the galaxy overview. The active hierarchy is resolved from the currently loaded Cosmic Horizons data. Missing hierarchy metadata is not inferred from a franchise name, pack name, or dimension ID. Systems without galaxy metadata remain in an explicit **Unassigned systems** group.

## Persistent visual layers

Open **Tools** and use the atlas layer switches:

- **Orbits** — miniature orbital diagrams around each system's star.
- **Labels** — system names.
- **Sectors** — explicit sector names from datapack metadata.
- **Guides** — visual reference ellipses; they are not physical boundaries.
- **Links** — visual association links between systems with the same explicit sector key.
- **Packs** — pack/source labels when present in the active data.
- **Routes** — only explicit inter-system links declared by datapack route metadata.

Layer switches are client preferences. They do not edit source datapacks.

## Filters

The filter control cycles through all, planets, moons/satellites, stars, other bodies, life metadata, atmosphere metadata, stations, asteroids, and black holes. In atlas mode a system remains visible if at least one body matches the selected filter. Life and atmosphere filters only inspect authored metadata; they do not calculate habitability.

## Data integrity

Galaxy positions inside a universe are accepted only from explicit `universe_galaxy_pos`, `galaxy_map_pos`, or `galaxy_location` fields. A galaxy's spiral glyph is illustrative, not a physical rendering of its morphology. Global atlas coordinates are accepted only from explicit atlas/system/galaxy/universe position fields. Standard body `pos` and `map_pos` remain system-local and are not used to fabricate distances between star systems. Systems without global coordinates receive a deterministic schematic placement and are counted in the map's provenance label.
