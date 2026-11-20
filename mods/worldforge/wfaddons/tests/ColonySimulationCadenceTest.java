package dev.worldforge.tests;

import dev.worldforge.settle.mc.ColonySimulationCadence;

public final class ColonySimulationCadenceTest {
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        int interval = 20;
        String[] ids = {"colony-alpha", "colony-beta", "colony-gamma", "colony-delta"};
        for (String id : ids) {
            int phase = ColonySimulationCadence.phase(id, interval);
            check(phase >= 0 && phase < interval, "phase must be inside configured interval");
            int occurrences = 0;
            for (int tick = 1; tick <= interval * 4; tick++) {
                if (ColonySimulationCadence.due(id, tick, interval)) {
                    occurrences++;
                    check(ColonySimulationCadence.stepNumber(id, tick, interval) == occurrences,
                            "colony-local step counter must be monotonic and gap-free");
                }
            }
            check(occurrences == 4, "every colony must run exactly once per interval");
            check(phase == ColonySimulationCadence.phase(id, interval), "phase must be stable");
        }

        int[] distribution = new int[interval];
        for (int i = 0; i < 4000; i++)
            distribution[ColonySimulationCadence.phase("colony-" + i, interval)]++;
        int min = Integer.MAX_VALUE, max = Integer.MIN_VALUE;
        for (int count : distribution) { min = Math.min(min, count); max = Math.max(max, count); }
        check(max - min < 35, "stable hashing should spread large colony sets across ticks");

        check(ColonySimulationCadence.elapsedSteps(100, 124) == 1.0,
                "one normal settlement interval equals one simulation step");
        check(ColonySimulationCadence.elapsedSteps(100, 220) == 5.0,
                "x5 time speed is preserved by the shared calendar delta");
        check(ColonySimulationCadence.elapsedSteps(100, 340) == 10.0,
                "x10 time speed is preserved by the shared calendar delta");
        check(ColonySimulationCadence.elapsedSteps(124, 124) == 0.0,
                "paused world time creates no simulation catch-up");
        check(ColonySimulationCadence.elapsedSteps(200, 100) == 0.0,
                "clock corrections never reverse simulation");

        System.out.println("ColonySimulationCadenceTest: OK");
    }
}
