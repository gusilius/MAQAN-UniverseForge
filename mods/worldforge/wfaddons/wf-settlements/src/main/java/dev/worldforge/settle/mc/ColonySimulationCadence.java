package dev.worldforge.settle.mc;

/**
 * Stable phase scheduling for colony simulation.
 *
 * Every colony is advanced once per configured interval, but its work is assigned
 * to a deterministic tick within that interval. The calendar delta is authoritative:
 * pauses produce no catch-up and speed changes retain their exact elapsed world time.
 */
public final class ColonySimulationCadence {
    private ColonySimulationCadence() {}

    public static int phase(String colonyId, int intervalTicks) {
        if (colonyId == null || colonyId.isBlank())
            throw new IllegalArgumentException("colonyId is required");
        if (intervalTicks < 1) throw new IllegalArgumentException("intervalTicks must be positive");
        int hash = 0x811C9DC5;
        for (int i = 0; i < colonyId.length(); i++) {
            hash ^= colonyId.charAt(i);
            hash *= 0x01000193;
        }
        hash ^= hash >>> 16;
        hash *= 0x7feb352d;
        hash ^= hash >>> 15;
        return Math.floorMod(hash, intervalTicks);
    }

    /** Tick numbers are one-based. Each colony is due exactly once in every interval. */
    public static boolean due(String colonyId, long serverTick, int intervalTicks) {
        if (serverTick < 1 || intervalTicks < 1) return false;
        return Math.floorMod(serverTick - 1L, intervalTicks) == phase(colonyId, intervalTicks);
    }

    /** One-based local simulation step for this colony at a due tick. */
    public static long stepNumber(String colonyId, long serverTick, int intervalTicks) {
        if (!due(colonyId, serverTick, intervalTicks))
            throw new IllegalArgumentException("colony is not due on this server tick");
        return Math.floorDiv(serverTick - 1L - phase(colonyId, intervalTicks), intervalTicks) + 1L;
    }

    /** Settlement.tick uses one simulation step per 24 shared world-calendar minutes. */
    public static double elapsedSteps(long previousWorldMinute, long currentWorldMinute) {
        if (previousWorldMinute < 0 || currentWorldMinute < 0)
            throw new IllegalArgumentException("world minutes cannot be negative");
        return Math.max(0L, currentWorldMinute - previousWorldMinute) / 24.0;
    }
}
