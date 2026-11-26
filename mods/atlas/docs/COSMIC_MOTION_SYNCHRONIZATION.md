# Navigator ↔ Cosmic Horizons motion synchronization

MAQAN now uses one client-side `CosmicMotionClock` for both the Navigator preview and the native Cosmic Horizons sky-body adapter.

## Shared phase sources

- Orbital phase: `CosmicMotionClock.orbitTicks(level, gameTime)`
- Axial phase: `CosmicMotionClock.rotationTicks(level, gameTime)`
- Both clocks sample Minecraft's level game time plus the render partial tick.
- The clock advances only when the sampled game time advances. Calling it from the Navigator and native renderer during the same frame does not double-advance the simulation.
- The active speed controls are applied once in the shared clock: Navigator orbit/rotation multiplier × `cosmic_orbits.json` simulation speed.
- The preview and the in-world adapter both use `NativeOrbitalSimulation` for positions. The in-world adapter writes the calculated position into Cosmic Horizons' own render data and its position-sort list; Cosmic Horizons still draws the planet.

## Pause and reset behavior

- Global motion disabled: both views stop and use source orientation/coordinates.
- Orbital motion disabled: orbit phase stops; axial spin can continue.
- Axial rotation disabled: spin phase stops; orbital motion can continue.
- Per-body orbit/spin disabled: only that body's corresponding motion is suppressed.
- Changing worlds or leaving a world resets the shared clock so the next world cannot inherit the previous world's phase.
- A new system state begins at the currently shared phase. This keeps Navigator and the native sky view aligned even if the Navigator is opened after the world has been running.

## Manual verification checklist

1. Load a Cosmic Horizons system dimension and note one planet's position in the Navigator.
2. Open/close the Navigator several times. The planet must not jump or accelerate when the preview is opened.
3. Compare the same planet in the native Cosmic Horizons sky view. Its orbit phase and axial orientation should match the preview at the same frame.
4. Pause orbit speed while leaving rotation speed active; only orbital movement should stop.
5. Pause rotation speed while leaving orbit speed active; only axial spin should stop.
6. Set global simulation speed to zero; all motion should stop in both views.
7. Change dimensions/worlds and return; no phase from the previous world should leak into the new world.
8. Test a system with a star, multiple planets, and a moon, then test two systems attached to the same dimension.

## Scope and caveats

This is client-side visual synchronization. It does not move Minecraft dimensions, chunks, entities, collision geometry, or server-side world time. The N-body path requires complete datapack initial conditions and is stateful; for a newly loaded system its integration begins when that system is first sampled. Kepler/presentation orbits are evaluated directly from the shared phase. A successful Gradle build validates source compatibility, not the final visual result in a running Cosmic Horizons 0.0.7.3 client.
