package dev.worldforge.army;

import dev.worldforge.mod.Json;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Optional Rust combat provider. A missing/unloaded provider returns null before ownership
 * is acquired; a provider failure after load fails closed and never runs Java combat in parallel.
 */
final class ArmyCombatCompute {
    record Round(double killA, double killB, double lossShareA, double lossShareB) {}
    record Result(List<Round> rounds) {}

    private ArmyCombatCompute() {}

    static Result trySimulate(List<Army> sideA, List<Army> sideB, Map<String, UnitType> types,
                              long seed, boolean defenderA) {
        final Class<?> provider;
        try {
            provider = Class.forName("dev.worldforge.worldsim.mc.NativeWorldSimulation", false,
                    ArmyCombatCompute.class.getClassLoader());
        } catch (ClassNotFoundException | NoClassDefFoundError unavailable) {
            return null;
        }
        try {
            Method load = provider.getMethod("load");
            if (!Boolean.TRUE.equals(load.invoke(null))) return null;
            Map<String,Object> request = new LinkedHashMap<>();
            request.put("schema_version", 1);
            request.put("seed", seed);
            request.put("defender_a", defenderA);
            request.put("side_a", encodeSide(sideA, types));
            request.put("side_b", encodeSide(sideB, types));
            byte[] input = Json.write(request).getBytes(StandardCharsets.UTF_8);
            byte[] response = (byte[]) provider.getMethod("advanceArmyCombat", byte[].class).invoke(null, (Object) input);
            if (response == null || response.length == 0) throw new IllegalStateException("Rust army combat returned an empty frame");
            Object parsed = Json.parse(new String(response, StandardCharsets.UTF_8));
            if (!(parsed instanceof Map<?,?> root) || !"ok".equals(root.get("status"))
                    || !(root.get("rounds") instanceof List<?> rawRounds))
                throw new IllegalStateException("Rust army combat rejected its snapshot: " + parsed);
            List<Round> rounds = new ArrayList<>(rawRounds.size());
            for (Object raw : rawRounds) {
                if (!(raw instanceof Map<?,?> row)) throw new IllegalStateException("Malformed Rust combat round");
                double killA = number(row.get("kill_a")), killB = number(row.get("kill_b"));
                double shareA = number(row.get("loss_share_a")), shareB = number(row.get("loss_share_b"));
                if (!finiteNonnegative(killA) || !finiteNonnegative(killB)
                        || !finiteNonnegative(shareA) || !finiteNonnegative(shareB)
                        || shareA > 1 || shareB > 1)
                    throw new IllegalStateException("Rust combat returned out-of-range casualty deltas");
                rounds.add(new Round(killA, killB, shareA, shareB));
            }
            if (rounds.size() > 8) throw new IllegalStateException("Rust combat exceeded round budget");
            return new Result(List.copyOf(rounds));
        } catch (InvocationTargetException error) {
            Throwable cause = error.getCause();
            throw new IllegalStateException("Rust army combat provider failed after acquiring ownership", cause);
        } catch (ReflectiveOperationException | RuntimeException error) {
            throw new IllegalStateException("Rust army combat contract failed after provider discovery", error);
        }
    }

    private static boolean finiteNonnegative(double value) { return Double.isFinite(value) && value >= 0; }
    private static double number(Object value) {
        if (!(value instanceof Number number)) throw new IllegalStateException("Rust combat result is missing a numeric field");
        return number.doubleValue();
    }

    private static List<Object> encodeSide(List<Army> armies, Map<String,UnitType> types) {
        List<Object> encoded = new ArrayList<>(armies.size());
        for (Army army : armies) {
            Map<String,Object> row = new LinkedHashMap<>();
            row.put("martial", army.martial);
            row.put("morale", army.morale);
            List<Object> units = new ArrayList<>();
            for (Map.Entry<String,Integer> entry : army.units.entrySet()) {
                UnitType type = types.get(entry.getKey());
                // Preserve unknown roster entries in headcount/casualty distribution, matching Java strength().
                Map<String,Object> unit = new LinkedHashMap<>();
                unit.put("id", entry.getKey());
                unit.put("count", entry.getValue());
                unit.put("attack", type == null ? 0.0 : type.attack);
                unit.put("defense", type == null ? 0.0 : type.defense);
                unit.put("counters", type == null ? Map.of() : type.counters);
                units.add(unit);
            }
            row.put("units", units);
            encoded.add(row);
        }
        return encoded;
    }
}
