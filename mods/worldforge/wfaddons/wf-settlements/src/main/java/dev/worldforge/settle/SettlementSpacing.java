package dev.worldforge.settle;

/**
 * Shared spatial invariant for canonical settlement founding.
 * A center distance strictly below 128 blocks is considered an overlap.
 */
public final class SettlementSpacing {
    public static final int MINIMUM_CENTER_DISTANCE_BLOCKS = 128;
    private static final long MINIMUM_DISTANCE_SQUARED =
            (long) MINIMUM_CENTER_DISTANCE_BLOCKS * MINIMUM_CENTER_DISTANCE_BLOCKS;

    private SettlementSpacing() { }

    /**
     * Returns true when the squared distance is below the founding separation.
     * Axis checks happen first so arbitrary long deltas cannot overflow when squared.
     */
    public static boolean conflicts(long dx, long dz) {
        if (dx <= -MINIMUM_CENTER_DISTANCE_BLOCKS || dx >= MINIMUM_CENTER_DISTANCE_BLOCKS
                || dz <= -MINIMUM_CENTER_DISTANCE_BLOCKS || dz >= MINIMUM_CENTER_DISTANCE_BLOCKS) {
            return false;
        }
        return dx * dx + dz * dz < MINIMUM_DISTANCE_SQUARED;
    }
}
