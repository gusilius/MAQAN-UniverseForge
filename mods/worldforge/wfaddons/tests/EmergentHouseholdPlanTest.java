package dev.worldforge.tests;

import dev.worldforge.worldsim.EmergentHouseholdPlan;
import java.util.*;

public final class EmergentHouseholdPlanTest {
    public static void main(String[] args) {
        Map<Integer, int[]> roles = new HashMap<>();
        Set<String> ids = new HashSet<>();
        for (int ordinal = 0; ordinal < 40; ordinal++) {
            int household = EmergentHouseholdPlan.householdIndex(ordinal);
            int[] counts = roles.computeIfAbsent(household, ignored -> new int[3]);
            counts[switch (EmergentHouseholdPlan.role(ordinal)) {
                case FATHER -> 0;
                case MOTHER -> 1;
                case CHILD -> 2;
            }]++;
            ids.add(EmergentHouseholdPlan.householdId("settlement-a", ordinal));
        }
        check(roles.size() == 10 && ids.size() == 10, "ten distinct households are formed");
        for (int[] counts : roles.values())
            check(counts[0] == 1 && counts[1] == 1 && counts[2] == 2,
                    "each household has two parents and two children");
        check(EmergentHouseholdPlan.householdId("settlement-a", 0)
                        .equals(EmergentHouseholdPlan.householdId("settlement-a", 3))
                        && !EmergentHouseholdPlan.householdId("settlement-a", 0)
                        .equals(EmergentHouseholdPlan.householdId("settlement-a", 4)),
                "household IDs remain stable across incremental batches");
        System.out.println("PASS: EmergentHouseholdPlanTest");
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
