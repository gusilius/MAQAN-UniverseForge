# Atlas Spatial Workspace

Atlas owns cosmic spatial presentation and navigation: universe, galaxies, sectors, star systems, stars, planets, moons, orbital paths and travel. WorldForge owns civilization simulation on supported worlds.

## Navigation scale

Universe → galaxy → sector → star system → orbital body → planet → surface/map handoff.

Transitions preserve selected object identity and provide a breadcrumb/back stack. Zoom levels use appropriate detail/LOD; a galaxy overview must not be a stretched solar-system view.

## System map

A system view must render the star and all known bodies with:
- orbital paths and body positions at a defined simulation timestamp;
- orbital period, semi-major axis, eccentricity, inclination and phase when source data provides them;
- axial rotation and rotation period when modeled;
- moons, rings, asteroid belts, comets and other catalogued bodies;
- selection, labels, scale controls, time controls and object inspector;
- route planning and entry/exit points.

Do not invent orbital parameters. Distinguish measured/source-provided, configured, derived and schematic values. If a source only supplies approximate coordinates, mark them approximate.

## Time synchronization

Atlas visualization and in-world celestial movement must consume the same authoritative ephemeris/time source. UI-only animation is not a substitute for actual world synchronization. Multiplayer must define server authority and client interpolation.

## Object inspectors

Star: classification, mass/luminosity/radius/temperature/age where available, companions and system membership.
Planet: type, radius/diameter, mass/gravity, atmosphere, climate, surface, biomes, resources, moons, rings, ownership/civilization link and source.
Moon: parent body, orbit, physical/environmental properties and source.
System: star hierarchy, body catalog, orbital stability/data quality, travel nodes and source.
Galaxy/sector: boundaries, systems, points of interest, routes and known/unexplored state.

## WorldForge handoff

A planet may expose a WorldForge civilization layer only when a compatible WorldForge world/realm mapping exists. The handoff preserves body ID and world identity. Atlas does not simulate populations, governments, settlements or armies. WorldForge does not replace Atlas orbital mechanics.

## Map tools

Layers, filters, search, bookmarks, route planner, measurement, coordinate readout, time scrubber, object comparison and source inspector. Tool windows may float, dock or join tab groups under the platform window manager.
