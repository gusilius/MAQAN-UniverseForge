# Navigation travel and galaxy map profiles

## Travel calculation API

The Java API `com.maqan.galacticnavigator.navigation.NavigatorTravelPlanner`
calculates routes without creating dimensions or moving players. It accepts origin
and destination coordinates plus a selected drive model:

- `SUBLIGHT`: continuous conventional travel.
- `HYPERDRIVE`: multiple range-limited jumps with spool-up time.
- `NAVIGATOR`: route plotting and skill/chart-dependent uncertainty.
- `WARP`: warp translation with hazard exposure.
- `WORMHOLE`, `MASS_RELAY`, `GATE`: require a discovered link supplied by the caller.

A plan contains ordered legs, duration, confidence, risk, a calculated arrival point
and an arrival safety radius. The server must revalidate the target dimension, world
border, build height and collision-safe arrival before completing any future journey.
The planner does not silently fall back to teleport when a required transit link is absent.

## User-defined galaxy partition profiles

`config/maqan_galactic_navigator/map_profiles.json` stores user-owned map profiles.
Supported display layouts are `HIERARCHY`, `QUADRANTS`, `CONCENTRIC_RINGS`,
`SEGMENTUMS` and `CUSTOM_REGIONS`. Names, ring radii, region bounds, parent regions
and explicit system memberships are data, not hardcoded lore. Profiles only organize
the map; they never create dimensions.

Example schema:

```json
{
  "profiles": [{
    "id": "my-map",
    "name": "My galactic atlas",
    "layout": "CUSTOM_REGIONS",
    "center_x": 0,
    "center_z": 0,
    "ring_radii": [1200, 3400, 8000],
    "region_names": ["Central", "Inner", "Outer", "Frontier"],
    "regions": [{
      "id": "frontier-west",
      "name": "Western Frontier",
      "parent_id": "",
      "min_x": -9000,
      "max_x": -3000,
      "min_z": -5000,
      "max_z": 5000,
      "system_ids": ["example:system_id"],
      "color": 1720353468
    }]
  }]
}
```

## Dimension generation boundary

Navigator map edits are metadata (coordinates, hierarchy, region membership and route
links). Dimension creation remains the responsibility of the separate world/dimension
generation script. A map edit must never imply that a Minecraft dimension has been
created or registered.
