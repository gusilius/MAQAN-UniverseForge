package dev.worldforge.troops;

import dev.worldforge.mod.Json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Deterministic field-supply ledger. One ration unit feeds one soldier for one day. */
public final class OperationalSupply {
    private OperationalSupply() {}

    public static final double MAX_READINESS = 1.0;
    public static final double STARVATION_LOSS_PER_DAY = 0.12;
    public static final double RECOVERY_PER_DAY = 0.025;

    public static final class State {
        /** Display/cache balance; the canonical ration balance is the army-supply TreasuryLedger account. */
        public double rations;
        public double readiness = MAX_READINESS;
        public double daysWithoutFullRations;
        public double consumed;
        public long lastProcessedWorldDay = -1;
    }

    /** World-persistent readiness state. Ration stock itself remains in the shared TreasuryLedger. */
    public static final class Store {
        private final Map<String, State> states = new LinkedHashMap<>();

        public synchronized State state(String key) {
            if (key == null || key.isBlank() || key.length() > 128)
                throw new IllegalArgumentException("supply owner key is required");
            return states.computeIfAbsent(key, ignored -> new State());
        }

        /** Applies at most one readiness transition per canonical world day. */
        public synchronized Report advance(String key, int soldiers, double days,
                                            double availableRations, double consumed, long worldDay) {
            State state = state(key);
            if (worldDay < 0) throw new IllegalArgumentException("world day cannot be negative");
            if (worldDay <= state.lastProcessedWorldDay) return null;
            double needed = demand(soldiers, days);
            if (!Double.isFinite(availableRations) || availableRations < 0
                    || !Double.isFinite(consumed) || consumed < 0 || consumed > needed + 1e-8
                    || consumed > availableRations + 1e-8)
                throw new IllegalArgumentException("invalid persisted ration settlement");
            state.rations = Math.max(0, availableRations - consumed);
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
            state.lastProcessedWorldDay = worldDay;
            Status status = shortage <= 1e-9 ? Status.SUPPLIED
                    : state.readiness < 0.35 ? Status.STARVING : Status.LOW;
            return new Report(status, needed, consumed, shortage, state.readiness);
        }

        public synchronized String toJson() {
            List<Object> rows = new ArrayList<>();
            for (Map.Entry<String, State> entry : states.entrySet()) {
                State value = entry.getValue();
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", entry.getKey());
                row.put("rations", value.rations);
                row.put("readiness", value.readiness);
                row.put("days_without_full_rations", value.daysWithoutFullRations);
                row.put("consumed", value.consumed);
                row.put("last_processed_world_day", value.lastProcessedWorldDay);
                rows.add(row);
            }
            Map<String, Object> root = new LinkedHashMap<>();
            root.put("format", "wf-operational-supply/1");
            root.put("states", rows);
            return Json.write(root);
        }

        public synchronized void fromJson(String json) {
            Object parsed = Json.parse(json == null ? "" : json);
            if (!(parsed instanceof Map<?, ?> root)
                    || !"wf-operational-supply/1".equals(root.get("format"))
                    || !(root.get("states") instanceof List<?> rows))
                throw new IllegalArgumentException("Invalid operational supply save");
            Map<String, State> restored = new LinkedHashMap<>();
            for (Object raw : rows) {
                if (!(raw instanceof Map<?, ?> row)
                        || !(row.get("id") instanceof String id) || id.isBlank() || id.length() > 128
                        || !(row.get("rations") instanceof Number rations)
                        || !(row.get("readiness") instanceof Number readiness)
                        || !(row.get("days_without_full_rations") instanceof Number shortageDays)
                        || !(row.get("consumed") instanceof Number consumed)
                        || !(row.get("last_processed_world_day") instanceof Number lastDay))
                    throw new IllegalArgumentException("Invalid operational supply state row");
                State state = new State();
                state.rations = rations.doubleValue();
                state.readiness = readiness.doubleValue();
                state.daysWithoutFullRations = shortageDays.doubleValue();
                state.consumed = consumed.doubleValue();
                state.lastProcessedWorldDay = lastDay.longValue();
                if (!Double.isFinite(state.rations) || state.rations < 0 || state.rations > 1_000_000
                        || !Double.isFinite(state.readiness) || state.readiness < 0 || state.readiness > 1
                        || !Double.isFinite(state.daysWithoutFullRations) || state.daysWithoutFullRations < 0
                        || !Double.isFinite(state.consumed) || state.consumed < 0
                        || state.lastProcessedWorldDay < -1 || restored.putIfAbsent(id, state) != null)
                    throw new IllegalArgumentException("Out-of-range or duplicate operational supply state: " + id);
            }
            states.clear();
            states.putAll(restored);
        }

        public synchronized void clear() { states.clear(); }
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
