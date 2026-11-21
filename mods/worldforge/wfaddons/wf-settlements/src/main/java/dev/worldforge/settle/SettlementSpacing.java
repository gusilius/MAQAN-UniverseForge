package dev.worldforge.settle;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Shared spatial invariant and deterministic fallback placement for canonical settlements.
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

    /**
     * Finds the nearest deterministic 128-block grid candidate that does not overlap
     * any occupied active settlement center. Returns null if the bounded search is full.
     */
    public static int[] findAvailablePosition(int originX, int originZ, List<long[]> occupied, int searchRadius) {
        if (searchRadius < 0) return null;
        int radius = Math.min(searchRadius, 4096);
        int steps = radius / MINIMUM_CENTER_DISTANCE_BLOCKS;
        List<int[]> candidates = new ArrayList<>((steps * 2 + 1) * (steps * 2 + 1));
        for (int gx = -steps; gx <= steps; gx++) {
            for (int gz = -steps; gz <= steps; gz++) {
                long x = (long) originX + (long) gx * MINIMUM_CENTER_DISTANCE_BLOCKS;
                long z = (long) originZ + (long) gz * MINIMUM_CENTER_DISTANCE_BLOCKS;
                if (x < Integer.MIN_VALUE || x > Integer.MAX_VALUE
                        || z < Integer.MIN_VALUE || z > Integer.MAX_VALUE) continue;
                candidates.add(new int[]{(int) x, (int) z});
            }
        }
        candidates.sort(Comparator
                .comparingLong((int[] p) -> {
                    long dx = (long) p[0] - originX, dz = (long) p[1] - originZ;
                    return dx * dx + dz * dz;
                })
                .thenComparingInt(p -> p[0])
                .thenComparingInt(p -> p[1]));
        List<long[]> centers = occupied == null ? List.of() : occupied;
        for (int[] candidate : candidates) {
            boolean available = true;
            for (long[] center : centers) {
                if (center == null || center.length < 2) continue;
                if (conflicts((long) center[0] - candidate[0], (long) center[1] - candidate[1])) {
                    available = false;
                    break;
                }
            }
            if (available) return candidate;
        }
        return null;
    }
}
