# Cosmic Horizons native orbital motion

MAQAN Galactic Navigator adds a client-side orbital controller for Cosmic Horizons 0.0.7.3. It updates the native Cosmic Horizons render data before its AFTER_SKY rendering pass.

## Native integration

Cosmic Horizons 0.0.7.3 builds per-dimension render_data_map and global_position_map lists from each active cosmic_data resource's planet_data entries. Its RenderMINTProcedure reads those lists to draw the original textured bodies, rings, clouds, atmospheres and lighting.

MAQAN updates the x/y/z coordinates in those native render entries and the matching encoded global-position strings. The original Cosmic Horizons renderer continues to draw the bodies. MAQAN does not create a second sphere renderer and does not replace Cosmic Horizons textures or materials.

- Planets orbit their parent star or black hole.
- Moons orbit their parent planet.
- Explicit parent links are preferred; nearest suitable parent is a fallback.
- Orbit positions are evaluated from the synchronized Minecraft game clock plus render partial tick.
- Axial rotation is applied independently to each body's native yaw.
- Axial tilt is applied to the native roll.
- Systems sharing the same attached dimension are merged in the same datapack/body order used by Cosmic Horizons.
- Dimensions, chunks, gravity and collision data are not moved.

This is a visual celestial simulation in the space sky. It does not physically move Minecraft dimensions or their terrain.

## In-game settings

Open Navigator → TOOLS → SPACE MOTION.

- Native celestial motion: master enable/disable. Disabling restores the original datapack positions and orientations.
- Orbital revolution: enable/disable orbital position calculation.
- Axial rotation: enable/disable individual body rotation.
- Simulation speed: 0x pauses; 1x advances one simulation tick per Minecraft tick; larger values accelerate.
- Planet orbit and moon orbit: fallback periods used only if the datapack has no period metadata.
- Axial period: fallback spin period used only if the datapack has no axial period or yaw_speed.

The settings file is created on first use at:

config/maqan_galactic_navigator/cosmic_orbits.json

## Optional per-body data

Add these optional properties to an individual body's object in planet_data or its GUI object_data entry. They are MAQAN metadata; Cosmic Horizons does not need to interpret them:

```json
{
  "parent_id": "central_star",
  "orbit_radius": 12000,
  "orbital_period_ticks": 240000,
  "orbit_phase_degrees": 25,
  "orbit_inclination_degrees": 4.5,
  "eccentricity": 0.03,
  "ascending_node_degrees": 12,
  "argument_of_periapsis_degrees": 6,
  "axial_rotation_period_ticks": 24000,
  "axial_tilt_degrees": 23.4
}
```

Supported explicit period fields:
- orbital_period_ticks, orbit_period_ticks, period_ticks
- orbital_period_seconds, orbit_period_seconds, period_seconds
- orbital_period_days, orbit_period_days, period_days

Per-body overrides are stored in the MAQAN config under bodies, keyed by systemId/bodyId:

```json
{
  "bodies": {
    "Sol/Earth": {
      "parentId": "Sol",
      "orbitPeriodTicks": 240000,
      "orbitRadius": 150000,
      "useOrbitOverrides": true,
      "phaseDegrees": 0,
      "inclinationDegrees": 0,
      "eccentricity": 0.0167,
      "axialRotationPeriodTicks": 1000,
      "useAxialTiltOverride": true,
      "axialTiltDegrees": 23.44,
      "orbitEnabled": true,
      "rotationEnabled": true
    }
  }
}
```

All periods are tick counts. Orbit radii and positions use the same coordinate units already present in Cosmic Horizons planet_data. Set useOrbitOverrides to true when a zero-valued phase, inclination or eccentricity must override datapack values. Set useAxialTiltOverride to true when the tilt override (including zero) must override datapack metadata. If the datapack does not define an orbital period, MAQAN uses the configurable fallback period; that is an animation default, not an astronomical measurement.

## Per-body editor

The body context menu opens a dedicated editor for the selected catalogue object. Parent selection cycles through the bodies in that system plus AUTO. Orbital and spin periods use ticks. Radius uses the same coordinate units as the source Cosmic Horizons positions. Phase, inclination and tilt use degrees. The editor persists every change immediately and supports resetting the selected body's overrides without resetting global settings.

The preview renderer and in-world adapter read the same per-body override keys and the same simulation multipliers, so the selected period/tilt/parent are intended to match between Navigator preview and Cosmic Horizons' native sky renderer.

## Compatibility and limitations

- Cosmic Horizons remains optional at mod-loading time. MAQAN exits cleanly if the mod or synchronized catalogue is absent.
- No Mixin or hard dependency is added. The integration uses reflection to access the public WorldVariables render_data_map and global_position_map fields, and writes only the client-side copy used by Cosmic Horizons rendering.
- Before modifying a dimension, MAQAN verifies that the number of catalogued physical bodies matches both native Cosmic Horizons lists. If the lists differ or their body order changes, the update is skipped rather than risking movement of the wrong object.
- The native map lists are merged in datapack order. MAQAN preserves that order and attaches a temporary maqan_body_id marker to each native render entry for subsequent order validation.
- Automatic parent inference is a convenience fallback. For binary systems, irregular satellites or intentionally non-nearest parents, define parent_id explicitly.
- CI compilation does not prove visual behavior. The final check must be done in a running Forge 1.20.1 instance with Cosmic Horizons 0.0.7.3 and the user's active datapacks. Check texture orientation, scale, depth order, orbit direction, body order and multiple systems sharing one dimension.
