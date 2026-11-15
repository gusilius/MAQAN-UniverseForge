package dev.worldforge.army;

import dev.worldforge.mod.Json;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Rust-owned pure strategic supply kernel; Java owns territory resolution and all effects. */
final class ArmyStrategicCompute {
    record Result(int armyId, double supply, double morale, int casualties, boolean starved, boolean lowSupply) {}
    private ArmyStrategicCompute() {}

    static List<Result> tryComputeSupply(List<Army> armies, Map<Integer, Boolean> homeByArmy, long day) {
        List<Army> active = armies.stream().filter(a -> a.men() > 0).toList();
        if (active.isEmpty()) return List.of();
        for (Army army : active) {
            if (army.id <= 0 || !Double.isFinite(army.supply) || army.supply < 0.0 || army.supply > 1.0
                    || !Double.isFinite(army.morale) || army.morale < 0.0 || army.morale > 1.0
                    || army.men() < 1 || !homeByArmy.containsKey(army.id)) return null;
        }
        final Class<?> provider;
        try {
            provider = Class.forName("dev.worldforge.worldsim.mc.NativeWorldSimulation", false,
                    ArmyStrategicCompute.class.getClassLoader());
        } catch (ClassNotFoundException | NoClassDefFoundError unavailable) { return null; }
        try {
            if (!Boolean.TRUE.equals(provider.getMethod("load").invoke(null))) return null;
            Map<String,Object> request = new LinkedHashMap<>();
            request.put("schema_version", 1);
            List<Object> rows = new ArrayList<>(active.size());
            for (Army army : active) {
                Map<String,Object> row = new LinkedHashMap<>();
                row.put("army_id", army.id);
                row.put("men", army.men());
                row.put("supply", army.supply);
                row.put("morale", army.morale);
                row.put("home", Boolean.TRUE.equals(homeByArmy.get(army.id)));
                rows.add(row);
            }
            request.put("armies", rows);
            byte[] input = Json.write(request).getBytes(StandardCharsets.UTF_8);
            if (input.length > 8 * 1024 * 1024) throw new IllegalStateException("Army strategic batch exceeds JNI frame limit");
            byte[] response = (byte[]) provider.getMethod("advanceArmyStrategic", byte[].class).invoke(null, (Object) input);
            if (response == null || response.length == 0) throw new IllegalStateException("Rust strategic army frame is empty");
            Object parsed = Json.parse(new String(response, StandardCharsets.UTF_8));
            if (!(parsed instanceof Map<?,?> root) || !"ok".equals(root.get("status"))
                    || !(root.get("armies") instanceof List<?> rowsOut) || rowsOut.size() != active.size())
                throw new IllegalStateException("Rust strategic army batch rejected snapshot or changed cardinality: " + parsed);
            List<Result> results = new ArrayList<>(rowsOut.size());
            Set<Integer> seen = new HashSet<>();
            for (int i=0;i<rowsOut.size();i++) {
                Object raw=rowsOut.get(i);
                if (!(raw instanceof Map<?,?> row)) throw new IllegalStateException("Malformed strategic army row");
                int id=integer(row.get("army_id"));
                double supply=number(row.get("supply")), morale=number(row.get("morale"));
                int casualties=integer(row.get("casualties"));
                if (id!=active.get(i).id || !seen.add(id) || !Double.isFinite(supply) || supply<0 || supply>1
                        || !Double.isFinite(morale) || morale<0 || morale>1 || casualties<0 || casualties>active.get(i).men()
                        || !(row.get("starved") instanceof Boolean starved)
                        || !(row.get("low_supply") instanceof Boolean low))
                    throw new IllegalStateException("Invalid or reordered Rust strategic army result");
                results.add(new Result(id,supply,morale,casualties,starved,low));
            }
            return List.copyOf(results);
        } catch (InvocationTargetException error) {
            throw new IllegalStateException("Rust strategic army provider failed after acquiring ownership",error.getCause());
        } catch (ReflectiveOperationException | RuntimeException error) {
            throw new IllegalStateException("Rust strategic army contract failed after provider discovery",error);
        }
    }
    private static int integer(Object value) {
        if (!(value instanceof Number n) || n.doubleValue()!=n.intValue()) throw new IllegalStateException("Missing integer in Rust strategic army frame");
        return n.intValue();
    }
    private static double number(Object value) {
        if (!(value instanceof Number n)) throw new IllegalStateException("Missing number in Rust strategic army frame");
        return n.doubleValue();
    }
}
