package dev.worldforge.worldsim;

/**
 * Deterministic cadence policy for population simulation.
 *
 * This class is intentionally allocation-free and does not retain Character objects.
 * Callers should keep indexed populations by simulation tier and process only the
 * tier's due slice under a fixed work budget; do not scan the full population each tick.
 */
public final class PopulationInteractionCadence {
    public enum Tier {
        VISIBLE(1, 1),
        NEAR_PLAYER(20, 3),
        ACTIVE_SETTLEMENT(100, 5),
        REMOTE_REGION(600, 10),
        ABSTRACT_WORLD(2400, 30);

        private final int intervalTicks;
        private final int maxWorkUnits;

        Tier(int intervalTicks, int maxWorkUnits) {
            this.intervalTicks = intervalTicks;
            this.maxWorkUnits = maxWorkUnits;
        }

        public int intervalTicks() { return intervalTicks; }
        public int maxWorkUnits() { return maxWorkUnits; }
    }

    private PopulationInteractionCadence() {}

    /** Stable phase offset spreads same-tier records across ticks without RNG state. */
    public static int phase(String stableCharacterId, Tier tier) {
        if (stableCharacterId == null || stableCharacterId.isEmpty() || tier == null)
            throw new IllegalArgumentException("character id and tier are required");
        int interval = tier.intervalTicks;
        return Math.floorMod(mix(stableCharacterId.hashCode()), interval);
    }

    /** O(1) due check; callers must avoid iterating every character to use it. */
    public static boolean due(String stableCharacterId, Tier tier, long worldTick) {
        if (worldTick < 0 || stableCharacterId == null || tier == null) return false;
        return Math.floorMod(worldTick, tier.intervalTicks) == phase(stableCharacterId, tier);
    }

    /** Simulation budget ceiling for a tier per world tick, not a population count. */
    public static int workBudget(Tier tier) {
        if (tier == null) throw new IllegalArgumentException("tier is required");
        return tier.maxWorkUnits;
    }

    /**
     * Stable deterministic hash mixer for phase assignment. Deliberately not used
     * for personality decisions: those must derive from persisted character traits,
     * relationships, goals and simulation seeds.
     */
    private static int mix(int value) {
        value ^= value >>> 16;
        value *= 0x7feb352d;
        value ^= value >>> 15;
        value *= 0x846ca68b;
        value ^= value >>> 16;
        return value;
    }
}
