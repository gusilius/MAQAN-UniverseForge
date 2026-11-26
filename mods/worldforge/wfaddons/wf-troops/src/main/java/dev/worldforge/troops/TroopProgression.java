package dev.worldforge.troops;

/**
 * Individual soldier experience and professional ranks.
 * XP is intentionally independent from the squad: splitting, merging or changing orders
 * must never reset a soldier's personal service record.
 */
public final class TroopProgression {
    private TroopProgression() {}

    public static final int MAX_XP = 100_000;
    private static final int[] RANK_XP = {0, 100, 300, 700, 1_500};

    public static int addXp(int current, int earned) {
        if (current < 0 || earned < 0) throw new IllegalArgumentException("XP cannot be negative");
        return (int) Math.min(MAX_XP, (long) Math.min(current, MAX_XP) + earned);
    }

    /** 0 recruit, 1 trained, 2 veteran, 3 elite, 4 champion. */
    public static int rank(int xp) {
        int safe = Math.max(0, Math.min(MAX_XP, xp));
        int rank = 0;
        for (int i = 1; i < RANK_XP.length; i++) if (safe >= RANK_XP[i]) rank = i;
        return rank;
    }

    public static double damageMultiplier(int rank) {
        return 1.0 + Math.max(0, Math.min(4, rank)) * 0.05;
    }

    public static double healthMultiplier(int rank) {
        return 1.0 + Math.max(0, Math.min(4, rank)) * 0.03;
    }

    public static double armorBonus(int rank) {
        return Math.max(0, Math.min(4, rank)) * 0.5;
    }
}
