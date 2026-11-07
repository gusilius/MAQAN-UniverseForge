package dev.worldforge.troops;

/**
 * Мораль отряда, как в Total War: потери и численный перевес врага роняют её, командир и покой поднимают; ниже ROUT_AT отряд бежит
 * и не слушает приказов, пока не придёт в себя (выше RALLY_AT и без врагов рядом). Чистая математика; шаг считает игровой слой раз в полсекунды.
 */
public final class Morale {
    private Morale() {}

    public static final double ROUT_AT = 0.2, RALLY_AT = 0.55, SHOCK = 1.2, FALL_RATE = 0.15, RECOVER_FIGHT = 0.01, RECOVER_CALM = 0.05, LEADER_BONUS = 0.1;
    public static final double AURA_DAMAGE = 1.15, HEAL_RANGE = 7;
    public static final int HEAL_TARGETS = 3;

    /**
     * @param morale       текущая мораль 0..1
     * @param aliveRatio   живых к начальному числу отряда (0..1)
     * @param lostNow      доля начального состава, потерянная с прошлого шага (удар по духу)
     * @param outnumber    врагов рядом на одного своего (0 - врагов нет)
     * @param leader       рядом командир с аурой
     * @param enemiesNear  есть враги рядом
     */
    public static double step(double morale, double aliveRatio, double lostNow, double outnumber, boolean leader, boolean enemiesNear, double dt) {
        morale -= lostNow * SHOCK;
        double target = 0.4 + 0.6 * clamp(aliveRatio, 0, 1);
        if (enemiesNear) target -= clamp(outnumber - 1, 0, 1) * 0.25;
        if (leader) target += LEADER_BONUS;
        target = clamp(target, 0, 1);
        if (morale > target) morale -= Math.min(morale - target, FALL_RATE * dt);
        else morale += Math.min(target - morale, (enemiesNear ? RECOVER_FIGHT : RECOVER_CALM) * dt);
        return clamp(morale, 0, 1);
    }

    /** Бежит ли отряд: упала ниже ROUT_AT, остановится, когда оправится. */
    public static boolean routed(boolean was, double morale, boolean enemiesNear) {
        if (morale < ROUT_AT) return true;
        return was && !(morale >= RALLY_AT && !enemiesNear);
    }

    /** Множитель урона: растерянные бьют слабее, рядом с командиром сильнее. */
    public static double damage(double morale, boolean leader) { return (0.6 + 0.4 * clamp(morale, 0, 1)) * (leader ? AURA_DAMAGE : 1); }

    /** Точка бегства: от центра врагов на dist блоков. */
    public static double[] flee(double cx, double cz, double ex, double ez, double dist) {
        double dx = cx - ex, dz = cz - ez, l = Math.hypot(dx, dz);
        if (l < 1e-6) { dx = 0; dz = 1; l = 1; }
        return new double[]{cx + dx / l * dist, cz + dz / l * dist};
    }

    /** Лечение: amount здоровья делится между самыми раненными (по доле здоровья), не больше targets получателей. Возвращает прибавку каждому. */
    public static double[] healPlan(double[] hp, double[] max, int targets, double amount) {
        int n = hp.length;
        double[] out = new double[n];
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) order[i] = i;
        java.util.Arrays.sort(order, java.util.Comparator.comparingDouble(i -> hp[i] / Math.max(1e-9, max[i])));
        int given = 0;
        for (int k = 0; k < n && given < targets; k++) {
            int i = order[k];
            double missing = max[i] - hp[i];
            if (missing <= 0.01) break;
            out[i] = Math.min(missing, amount);
            given++;
        }
        return out;
    }

    private static double clamp(double v, double lo, double hi) { return Math.max(lo, Math.min(hi, v)); }
}
