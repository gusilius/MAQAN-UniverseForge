package dev.worldforge.worldsim;

import java.util.Objects;

/**
 * Deterministic founding-household layout for autonomous settlement populations.
 * Each four-person household starts with two adult partners followed by two children.
 * The plan contains no Character identities; WorldSim materializes every member through
 * the canonical wf-people registry and wf-court marriage authority.
 */
public final class EmergentHouseholdPlan {
    public enum Role { FATHER, MOTHER, CHILD }

    private EmergentHouseholdPlan() {}

    public static int householdIndex(int residentOrdinal) {
        if (residentOrdinal < 0) throw new IllegalArgumentException("resident ordinal must be non-negative");
        return residentOrdinal / 4;
    }

    public static Role role(int residentOrdinal) {
        if (residentOrdinal < 0) throw new IllegalArgumentException("resident ordinal must be non-negative");
        return switch (residentOrdinal % 4) {
            case 0 -> Role.FATHER;
            case 1 -> Role.MOTHER;
            default -> Role.CHILD;
        };
    }

    public static String householdId(String bandId, int residentOrdinal) {
        String band = Objects.requireNonNull(bandId, "bandId").trim();
        if (band.isEmpty()) throw new IllegalArgumentException("band id is required");
        return band + "/household/" + householdIndex(residentOrdinal);
    }
}
