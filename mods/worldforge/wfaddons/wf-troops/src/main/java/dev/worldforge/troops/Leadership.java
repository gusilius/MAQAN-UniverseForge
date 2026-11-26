package dev.worldforge.troops;

/** Deterministic leadership bonuses shared by squad simulation and tactical AI. */
public final class Leadership {
    private Leadership() {}

    public static final double COMMANDER_BASE_AURA = 12.0;
    public static final double HERO_AURA = 4.0;
    public static final double RANK_AURA = 1.5;
    public static final double MAX_AURA = 32.0;
    public static final int BASE_COMMAND_CAPACITY = 20;
    public static final int HERO_COMMAND_CAPACITY = 10;
    public static final int RANK_COMMAND_CAPACITY = 4;

    /** Unit aura is preserved; an appointed commander and hero rank add to it. */
    public static double aura(double unitAura, boolean appointedCommander, boolean hero, int leadershipRank) {
        double base = Double.isFinite(unitAura) ? Math.max(0, unitAura) : 0;
        int rank = Math.max(0, Math.min(10, leadershipRank));
        double bonus = (appointedCommander ? COMMANDER_BASE_AURA : 0)
                + (hero ? HERO_AURA : 0) + rank * RANK_AURA;
        return Math.min(MAX_AURA, base + bonus);
    }

    public static int commandCapacity(boolean hero, int leadershipRank) {
        int rank = Math.max(0, Math.min(10, leadershipRank));
        return BASE_COMMAND_CAPACITY + (hero ? HERO_COMMAND_CAPACITY : 0) + rank * RANK_COMMAND_CAPACITY;
    }

    public static double moraleMultiplier(boolean hero, int leadershipRank) {
        int rank = Math.max(0, Math.min(10, leadershipRank));
        return 1.0 + (hero ? 0.10 : 0) + rank * 0.02;
    }
}
