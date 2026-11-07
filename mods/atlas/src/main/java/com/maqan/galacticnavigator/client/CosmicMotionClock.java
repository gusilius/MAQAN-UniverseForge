package com.maqan.galacticnavigator.client;

import net.minecraft.world.level.Level;

/**
 * One client-side simulation clock shared by the Navigator preview,
 * MAQAN's Kepler/N-body model and Cosmic Horizons' native sky renderer.
 *
 * The clock advances once per observed game-time delta, not once per renderer.
 * Calling it from multiple render paths at the same partial tick therefore
 * returns exactly the same phase and cannot make planets run faster.
 */
public final class CosmicMotionClock {
    private static Level level;
    private static double lastGameTime = Double.NaN;
    private static double orbitTicks;
    private static double rotationTicks;

    private CosmicMotionClock() {}

    public static synchronized double orbitTicks(Level currentLevel, double gameTime) {
        sample(currentLevel, gameTime);
        return orbitTicks;
    }

    public static synchronized double rotationTicks(Level currentLevel, double gameTime) {
        sample(currentLevel, gameTime);
        return rotationTicks;
    }

    private static void sample(Level currentLevel, double gameTime) {
        if (currentLevel == null) {
            level = null;
            lastGameTime = Double.NaN;
            orbitTicks = 0.0;
            rotationTicks = 0.0;
            return;
        }
        if (!Double.isFinite(gameTime)) return;
        if (level != currentLevel || !Double.isFinite(lastGameTime) || gameTime < lastGameTime) {
            level = currentLevel;
            lastGameTime = gameTime;
            orbitTicks = 0.0;
            rotationTicks = 0.0;
            return;
        }

        double delta = gameTime - lastGameTime;
        if (delta <= 0.0) return;

        CosmicOrbitSettings.Config settings = CosmicOrbitSettings.get();
        if (settings.enabled) {
            if (settings.orbitalMotion) {
                orbitTicks += delta * NavigatorPreferences.orbitMultiplier() * settings.simulationSpeed;
            }
            if (settings.axialRotation) {
                rotationTicks += delta * NavigatorPreferences.rotationMultiplier() * settings.simulationSpeed;
            }
        }
        lastGameTime = gameTime;
    }
}
