# Astrophysical object catalogue and rendering

MAQAN's Navigator treats Cosmic Horizons datapack objects as a typed catalogue. The scanner does not generate replacement systems and does not turn unknown entries into planets.

## Recognized object classes

- Stars: ordinary stars, protostars, red giants, white dwarfs, neutron stars/pulsars and quasars.
- Compact objects: black holes, with a separate accretion-disc atlas marker.
- Interstellar structures: nebulae, molecular clouds, gas/dust clouds and supernova remnants.
- Small bodies: moons, asteroids/asteroid belts and comets.
- Infrastructure: stations, shipyards, jump gates, wormholes and Lagrange points.
- Exploration/tactical markers: anomalies, wrecks, debris, beacons, signals and ship contacts.

The classification is based on source physical type, GUI category and GUI display type. Unknown custom types remain generic catalogue objects. Source metadata is copied through where present (nebula composition/density/colour, stellar/spectral class, luminosity, compact-object properties, comet tails, belt density and other source fields).

## Atlas presentation

- Nebulae use a soft multi-lobed cloud marker. Colours can be supplied as `nebula_color`, `cloud_color`, `bloom_color` and `core_color`.
- Black holes use a dark central disc with an accretion annulus. `accretion_disk_color` can tint the annulus.
- Comets use a bright nucleus and two independently tinted tail strokes. `tail_length`, `dust_tail_color` and `ion_tail_color` are optional metadata.
- These markers are catalogue/atlas representations. They do not create Minecraft blocks, chunks, collision bodies or a volumetric nebula in Cosmic Horizons' 3D sky renderer.

## MAQAN spatial metadata

The scanner preserves these optional MAQAN-authored fields from the system root,
GUI category, or individual GUI body (body values take precedence):

- Volume geometry: `volume_center`, `volume_position`, `volume_radius`,
  `volume_extent`, `volume_width`, `volume_height`, `volume_depth`,
  `volume_shape`, `volume_orientation`, `volume_render_scale`,
  `volume_opacity`, `volume_color`.
- Nebula sampling: `nebula_particle_count` / `nebula_sample_count` (bounded
  visual samples, not a physical gas-particle count).
- Nebula geometry: `nebula_center`, `nebula_position`, `nebula_extent`.
- Collision and orbital spacing: `collision_radius`, `physical_radius`,
  `collision_avoidance`, `orbit_clearance`, `minimum_orbit_separation`.
- Asteroid belt presentation: `belt_color`, `belt_inner_radius`,
  `belt_outer_radius`, `belt_inclination_degrees`, `belt_node_degrees`,
  `belt_center_radius`, `belt_width`, `belt_rotation_period_ticks`,
  `belt_phase_degrees`, `belt_thickness`, `asteroid_count`,
  `particle_count`, `particle_density`.
- Small-body presentation: `tail_direction`, `tail_orientation`, `coma_color`.
- Optional route-environment values: `hazard_level`, `radiation_level`,
  `navigation_penalty`.

These names are MAQAN metadata, not claims that Cosmic Horizons 0.0.7.3
natively interprets them. Values are copied verbatim; the scanner does not infer
physical units or convert a render scale into a collision radius.

The shared orbital solver now applies conservative radial-lane spacing to
siblings around the same resolved parent. It separates periapsis/apoapsis
envelopes using explicit physical radii when supplied, otherwise a conservative
fallback clearance. This is a conservative radial-lane safeguard, not a full orbital
perturbation solver. Dynamically integrated N-body bodies also receive a soft
near-collision steering acceleration using `collision_radius` or
`physical_radius` (0.5 coordinate units when neither is authored). This is
avoidance behavior, not a hard collision guarantee. Crossing orbital planes,
high-speed impacts, high-eccentricity perturbations and multi-body encounters
still require runtime validation.

## Scope of this stage

This stage adds type recognition, metadata preservation, Navigator filters and atlas visuals. It deliberately does not inject arbitrary nebula meshes into Cosmic Horizons' renderer: that needs a separately verified extension point in the exact Cosmic Horizons build and a defined representation for large-scale space volumes. Planetary motion and its shared clock remain separate from stationary nebula volumes and trajectory-based small bodies.

## Shared in-world environment snapshot

`CosmicEnvironmentCatalog.snapshot(systemId, gameTime)` now exposes a
system-scoped immutable snapshot for nebulae, asteroid belts and comets. It
reads the already-normalized `GalaxyCatalog`; it is not another datapack
scanner. Moving object positions are sampled through
`NativeOrbitalSimulation.coordinate(...)`, the same solver used by the
Navigator and the Cosmic Horizons orbit adapter.

The snapshot retains explicit volume center/extent/radius, belt radii/width,
thickness, density, particle count, inclination/node and comet tail metadata.
Unknown measurements remain unknown. In particular, `belt_width` is not
silently interpreted as an inner/outer radius, and incomplete volume extents
are not treated as valid 3D volumes.

This is a shared data contract only. It does not yet draw volumetric nebulae,
asteroid particles or comet meshes in the game sky. That renderer must be
attached to a verified Cosmic Horizons 0.0.7.3 render transform and must keep
each object in its own system coordinate frame; the native renderer's matrix,
depth ordering and event timing must be confirmed before adding geometry.

## Deterministic asteroid-belt geometry

`CosmicEnvironmentGeometry.asteroidBelt(snapshot, gameTime)` converts a
catalogue belt into a deterministic point-cloud snapshot in the source
Cosmic Horizons system coordinate frame. It uses authored inner/outer radii,
or an explicitly authored center radius plus full radial width. It does not
reinterpret `belt_width` as a diameter or infer orbital dimensions from
display scale.

Particle positions use a stable system/body seed, area-uniform radial
distribution, authored inclination and ascending node, and optional
`belt_rotation_period_ticks` / `belt_phase_degrees`. An authored particle
count is capped at 8192 for a bounded client-side geometry batch; when absent,
512 is only a presentation sampling default, not a claimed physical asteroid
count. Missing or ambiguous radial geometry produces no particle batch.

This produces geometry inputs only. It is not yet submitted to the Cosmic
Horizons sky renderer. The final in-world draw pass still requires the exact
0.0.7.3 native sky projection/model-view contract to be verified; no vanilla
world-space transform is assumed.

## Nebula and comet geometry snapshots

`CosmicEnvironmentGeometry.nebulaVolume(...)` builds a deterministic,
bounded 3D point sample inside an explicitly authored ellipsoid. It supports
volume extents or a radius, optional render scale and opacity, and Euler
orientation supplied as a JSON `[x,y,z]` degree array or an object with
`x/y/z` or `pitch/yaw/roll` degree fields. Unsupported orientation formats
fall back to no rotation. The visual sample count is bounded at 4096 and is
not interpreted as a physical gas-particle count.

`CosmicEnvironmentGeometry.comet(...)` builds separate tapered dust and ion
tail ribbons in system coordinates, with a coma radius when authored. The
ribbon has subtle time-based turbulence. It requires a valid direction and
positive authored tail length. The current fallback direction is radial away
from a uniquely resolved parent star; it is not a full anti-velocity model,
so a datapack-authored `tail_direction` remains the authoritative choice for
eccentric or multi-star trajectories.

These are geometry snapshots only; they are not yet submitted to the native
Cosmic Horizons sky renderer.

## Manual verification

1. Load a datapack that defines a nebula, black hole, comet and asteroid belt in Cosmic Horizons GUI object data.
2. Confirm each is listed once under the correct system and has the correct object kind.
3. Cycle Navigator filters through NEBULAE, COMETS and WRECKS, and confirm each filter includes only its own class.
4. Verify the nebula cloud, black-hole accretion disc and comet tails remain visible with orbit lines disabled.
5. Confirm that missing optional colours and numeric values fall back safely without crashing the Navigator.
