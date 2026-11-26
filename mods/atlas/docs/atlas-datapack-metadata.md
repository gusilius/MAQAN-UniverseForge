# Galactic atlas datapack metadata

The navigator does not ship named galaxies, sectors, factions, or route networks. It builds its hierarchy from the active Cosmic Horizons resource data and optional metadata supplied by datapacks.

## Hierarchy

Optional fields can be placed on the Cosmic Horizons system root, GUI category, or individual GUI body. More specific values are retained on the body. Supported grouping fields:

- `universe_id` / `universe` / `cosmos_id`
- `galaxy_id` / `galaxy_name` / `galaxy`
- `sector_id` / `sector_name` / `sector`

Missing values remain unassigned. The navigator must not infer a galaxy from a display name, namespace, dimension, or pack name.

## Global atlas coordinates

Use one of the following optional pairs on the system root or its central star:

- `system_pos: [x, y, z]` (when authored in the shared atlas coordinate frame)
- `galaxy_pos: [x, y, z]`
- `universe_pos: [x, y, z]`
- `galactic_pos: [x, y, z]`
- `galaxy_position: { "x": 0, "z": 0 }`
- `universe_position: { "x": 0, "z": 0 }`
- `atlas_x` + `atlas_z`, `galaxy_x` + `galaxy_z`, or `universe_x` + `universe_z`

These coordinates are global map coordinates, not a planet's ordinary Cosmic Horizons `pos` / `map_pos`. The navigator preserves authored relative placement. If no global coordinates exist, it uses a clearly labelled deterministic schematic layout; it never treats system-local coordinates as galactic distances.

## Optional route metadata

The atlas route layer reads explicit references from these fields on a system root or body:

- `hyperspace_routes`
- `jump_routes`
- `travel_routes`
- `system_routes`
- `connected_systems`
- `connections`
- `routes`

A route may be a string system ID/name, an array of references, or an object containing a target field such as `to`, `target`, `destination`, `target_system`, `destination_system`, `system_id`, or `id`. References are drawn only when they resolve to a system in the currently visible atlas. No route is invented from proximity or shared sector membership.

## Visual layers

The Tools popover exposes independent persistent atlas layers:

- **Orbits** — schematic miniature orbital diagrams inside each system marker.
- **Labels** — system names.
- **Sectors** — sector names when supplied by the datapack.
- **Guides** — map reference ellipses; visual guides only, not physical boundaries.
- **Sector links** — subtle links between systems that share an explicit sector value. These are association lines, not borders or travel routes.
- **Packs** — source pack labels when the active data provides a `pack`, `source_pack`, or `datapack` value.
- **Routes** — explicit route references only.

The layer preferences are client-side and do not modify datapacks or Cosmic Horizons data.

## Atlas filters

The existing filter control applies to both the system atlas and the body list. It can show systems or bodies containing planets, moons/satellites, stars, other object kinds, life metadata, atmosphere metadata, stations, asteroids, or black holes. A system is included when at least one of its bodies matches the active filter. The life and atmosphere filters inspect explicit datapack fields; they do not estimate habitability.

## Galaxy cartography

The atlas can render a universe-level **Galaxies** view. It groups active Cosmic Horizons systems by explicit `galaxy_id` / `galaxy` metadata, then drills into a selected galaxy to show its star systems. Systems without galaxy metadata remain in an explicit **Unassigned systems** group; they are not silently assigned to a named galaxy.

Optional universe-level galaxy coordinates may be authored on a system root or its GUI body using `universe_galaxy_pos`, `galaxy_map_pos`, or `galaxy_location`. The value may be a three-number array or an object with `x` and `z` (or `x` and `y`) coordinates. These are galaxy positions inside the universe, not star-system positions or planet-local coordinates. When absent, the galaxy map uses a deterministic schematic layout and labels its provenance.

The procedural spiral glyph is a visual symbol only. It does not claim to reproduce a galaxy's physical shape, size, or arm structure. Galaxy filtering still uses the selected body's explicit metadata, and opening a galaxy only changes the current atlas scope; it does not modify datapacks.
