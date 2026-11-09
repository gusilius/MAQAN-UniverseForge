package dev.worldforge.troops;

/** Deterministic field-supply ledger. One ration unit feeds one soldier for one day. */
public final class OperationalSupply {
    private OperationalSupply() {}

    public static final double MAX_READINESS = 1.0;
    public static final double STARVATION_LOSS_PER_DAY = 0.12;
    public static final double RECOVERY_PER_DAY = 0.025;

    public static final class State {
        public double rations;
        public double readiness = MAX_READINESS;
        public double daysWithoutFullRations;
        public double consumed;
    }

    public enum Status { SUPPLIED, LOW, STARVING }

    public record Report(Status status, double demanded, double consumed, double shortage, double readiness) {}

    public static double demand(int soldiers, double days) {
        if (soldiers < 0 || !Double.isFinite(days) || days < 0) throw new IllegalArgumentException("invalid supply demand");
        return soldiers * days;
    }

    public static double deliver(State state, double rations) {
        require(state);
        if (!Double.isFinite(rations) || rations < 0) throw new IllegalArgumentException("rations must be finite and nonnegative");
        state.rations = Math.min(1_000_000, state.rations + rations);
        return rations;
    }

    public static Report step(State state, int soldiers, double days) {
        require(state);
        if (soldiers < 0 || !Double.isFinite(days) || days < 0 || days > 365)
            throw new IllegalArgumentException("invalid supply simulation step");
        double needed = demand(soldiers, days);
        double consumed = Math.min(state.rations, needed);
        state.rations -= consumed;
        state.consumed += consumed;
        double shortage = Math.max(0, needed - consumed);
        if (needed > 0 && shortage > 1e-9) {
            double fraction = shortage / needed;
            state.daysWithoutFullRations += days * fraction;
            state.readiness = clamp(state.readiness - days * fraction * STARVATION_LOSS_PER_DAY, 0, 1);
        } else {
            state.daysWithoutFullRations = Math.max(0, state.daysWithoutFullRations - days);
            state.readiness = clamp(state.readiness + days * RECOVERY_PER_DAY, 0, 1);
        }
        Status status = shortage <= 1e-9 ? Status.SUPPLIED
                : state.readiness < 0.35 ? Status.STARVING : Status.LOW;
        return new Report(status, needed, consumed, shortage, state.readiness);
    }

    public static double combatMultiplier(State state) { require(state); return 0.65 + 0.35 * clamp(state.readiness, 0, 1); }
    public static double movementMultiplier(State state) { require(state); return 0.70 + 0.30 * clamp(state.readiness, 0, 1); }

    private static void require(State state) { if (state == null) throw new IllegalArgumentException("supply state is required"); }
    private static double clamp(double value, double min, double max) { return Math.max(min, Math.min(max, value)); }
}
