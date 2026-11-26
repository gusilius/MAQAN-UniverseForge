package com.maqan.galacticnavigator.navigation;

/** One monotonic animation timestamp shared by all MAQAN UI components in a frame. */
public final class MaqanAnimationClock {
    private static volatile long frameNanos = System.nanoTime();
    private static final float MOTION_SCALE = readMotionScale();

    private MaqanAnimationClock() {}

    private static float readMotionScale() {
        try {
            float value = Float.parseFloat(System.getProperty("maqan.motion.scale", "1.0"));
            return Float.isFinite(value) ? Math.max(0.0f, Math.min(1.0f, value)) : 1.0f;
        } catch (RuntimeException ignored) { return 1.0f; }
    }

    /** Call once at the beginning of a screen render pass. */
    public static void beginFrame() {
        frameNanos = System.nanoTime();
    }

    /** Monotonic timestamp captured for this frame; use for elapsed-time calculations. */
    public static long frameNanos() { return frameNanos; }

    /** Stable seconds value for every native component composed during this frame. */
    public static float frameTimeSeconds() {
        if (reducedMotion()) return 0.0f;
        return (float) ((frameNanos % 120_000_000_000L) / 1_000_000_000.0);
    }

    /** Apply the user's motion preference to a nominal animation duration. */
    public static double durationSeconds(double nominalSeconds) {
        if (!Double.isFinite(nominalSeconds) || nominalSeconds <= 0.0) return 0.0;
        return nominalSeconds * MOTION_SCALE;
    }

    public static boolean reducedMotion() { return MOTION_SCALE <= 0.05f; }
}
