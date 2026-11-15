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
    record Battle(List<Army> sideA, List<Army> sideB, long seed, boolean defenderA) {}

    private static final int MAX_BATCH_BATTLES = 4096;
    private ArmyCombatCompute() {}

    static List<Result> trySimulateBatch(List<Battle> battles, Map<String, UnitType> types) {
        if (battles.isEmpty()) return List.of();
        if (battles.size() > MAX_BATCH_BATTLES)
            throw new IllegalArgumentException("Army combat batch exceeds " + MAX_BATCH_BATTLES + " battles");
        final Class<?> provider;
        try {
            provider = Class.forName("dev.worldforge.worldsim.mc.NativeWorldSimulation", false,
                    ArmyCombatCompute.class.getClassLoader());
        } catch (ClassNotFoundException | NoClassDefFoundError unavailable) {
            return null;
        }
        try {
            if (!Boolean.TRUE.equals(provider.getMethod("load").invoke(null))) return null;
            Map<String,Object> request = new LinkedHashMap<>();
            request.put("schema_version", 1);
            List<Object> encodedBattles = new ArrayList<>(battles.size());
            for (Battle battle : battles) {
                Map<String,Object> row = new LinkedHashMap<>();
                row.put("schema_version", 1);
                row.put("seed", battle.seed());
                row.put("defender_a", battle.defenderA());
                row.put("side_a", encodeSide(battle.sideA(), types));
                row.put("side_b", encodeSide(battle.sideB(), types));
                encodedBattles.add(row);
            }
            request.put("battles", encodedBattles);
            byte[] input = Json.write(request).getBytes(StandardCharsets.UTF_8);
            if (input.length > 8 * 1024 * 1024)
                throw new IllegalStateException("Army combat batch exceeds the 8 MiB JNI frame limit");
            byte[] response = (byte[]) provider.getMethod("advanceArmyCombatBatch", byte[].class)
                    .invoke(null, (Object) input);
            if (response == null || response.length == 0)
                throw new IllegalStateException("Rust army combat batch returned an empty frame");
            Object parsed = Json.parse(new String(response, StandardCharsets.UTF_8));
            if (!(parsed instanceof Map<?,?> root) || !"ok".equals(root.get("status"))
                    || !(root.get("battles") instanceof List<?> rawBattles))
                throw new IllegalStateException("Rust army combat batch rejected its snapshot: " + parsed);
            if (rawBattles.size() != battles.size())
                throw new IllegalStateException("Rust army combat batch cardinality mismatch");
            List<Result> results = new ArrayList<>(rawBattles.size());
            for (Object raw : rawBattles) results.add(parseResult(raw));
            return List.copyOf(results);
        } catch (InvocationTargetException error) {
            throw new IllegalStateException("Rust army combat batch provider failed after acquiring ownership",
                    error.getCause());
        } catch (ReflectiveOperationException | RuntimeException error) {
            throw new IllegalStateException("Rust army combat batch contract failed after provider discovery", error);
        }
    }

    private static Result parseResult(Object parsed) {
        if (!(parsed instanceof Map<?,?> root) || !"ok".equals(root.get("status"))
                || !(root.get("rounds") instanceof List<?> rawRounds))
            throw new IllegalStateException("Malformed Rust combat result: " + parsed);
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
    }

    private static boolean finiteNonnegative(double value) { return Double.isFinite(value) && value >= 0; }
    private static double number(Object value) {
        if (!(value instanceof Number number))
            throw new IllegalStateException("Rust combat result is missing a numeric field");
        return number.doubleValue();
    }

    private static List<Object> encodeSide(List<Army> armies, Map<String,UnitType> types) {
        List<Object> encoded = new ArrayList<>(armies.size());
        for (Army army : armies) {
            Map<String,Object> row = new LinkedHashMap<>();
            row.put("martial", army.martial);
            row.put("morale", army.morale);
            List<Object> units = new ArrayList<>(army.units.size());
            for (Map.Entry<String,Integer> entry : army.units.entrySet()) {
                UnitType type = types.get(entry.getKey());
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
