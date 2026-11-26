package dev.worldforge.troops;

import java.util.*;

/**
 * Связь физических солдат с абстрактными армиями wf-army и с казной. Один солдат в мире = SCALE человек в армии (как размер отряда в Total War).
 * Призыв снимает людей с армии, павшие в мире потеряны насовсем, выжившие возвращаются при роспуске. Чистая логика.
 */
public final class Muster {
    private Muster() {}

    public static final int SCALE = 10;
    public static final double DESERT_CHANCE = 0.25;

    /** Соответствие типов армии типам солдат; если в units.json нет, берутся эти. */
    public static Map<String, String> defaultMap() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("levy", "levy_spear"); m.put("spear", "pikeman"); m.put("archer", "archer"); m.put("cavalry", "light_cavalry"); m.put("knight", "knight"); m.put("siege", "ram");
        return m;
    }

    /** One physical soldier and the exact strategic population type consumed to create it. */
    public record Soldier(String armyUnitId, String troopUnitId) {
        public Soldier {
            if (armyUnitId == null || !armyUnitId.matches("[a-z0-9_.:-]{1,64}"))
                throw new IllegalArgumentException("invalid strategic army unit ID");
            if (troopUnitId == null || !troopUnitId.matches("[a-z0-9_.:-]{1,64}"))
                throw new IllegalArgumentException("invalid physical troop unit ID");
        }
    }

    public record Plan(Map<String, Integer> soldiers, Map<String, Integer> menTaken,
                       List<Soldier> allocations) {
        public Plan {
            soldiers = Collections.unmodifiableMap(new LinkedHashMap<>(soldiers));
            menTaken = Collections.unmodifiableMap(new LinkedHashMap<>(menTaken));
            allocations = List.copyOf(allocations);
            if (!allocations.isEmpty()) {
                Map<String, Integer> byTroop = new LinkedHashMap<>();
                Map<String, Integer> byArmy = new LinkedHashMap<>();
                for (Soldier soldier : allocations) {
                    byTroop.merge(soldier.troopUnitId(), 1, Integer::sum);
                    byArmy.merge(soldier.armyUnitId(), SCALE, Math::addExact);
                }
                if (!byTroop.equals(soldiers) || !byArmy.equals(menTaken))
                    throw new IllegalArgumentException("muster allocations do not reconcile with roster deltas");
            }
        }
        public Plan(Map<String, Integer> soldiers, Map<String, Integer> menTaken) {
            this(soldiers, menTaken, List.of());
        }
        public int total() { return soldiers.values().stream().mapToInt(Integer::intValue).sum(); }
    }

    /** Creates a deterministic deployment plan and preserves the strategic source type per physical soldier. */
    public static Plan plan(Map<String, Integer> armyUnits, int maxSoldiers, Map<String, String> map) {
        if (armyUnits == null || map == null || maxSoldiers < 0)
            throw new IllegalArgumentException("valid army roster, unit map and deployment limit are required");
        Map<String, Integer> soldiers = new LinkedHashMap<>(), taken = new LinkedHashMap<>();
        List<Soldier> allocations = new ArrayList<>();
        int left = maxSoldiers;
        // ArmyEngine stores roster types in insertion order; that order defines deterministic mobilization priority.
        for (var e : armyUnits.entrySet()) {
            String troop = map.get(e.getKey());
            if (troop == null || left <= 0) continue;
            if (e.getValue() == null || e.getValue() < 0)
                throw new IllegalArgumentException("negative/invalid strategic army count for " + e.getKey());
            int can = Math.min(left, e.getValue() / SCALE);
            if (can <= 0) continue;
            soldiers.merge(troop, can, Integer::sum);
            taken.merge(e.getKey(), Math.multiplyExact(can, SCALE), Integer::sum);
            for (int i = 0; i < can; i++) allocations.add(new Soldier(e.getKey(), troop));
            left -= can;
        }
        return new Plan(soldiers, taken, allocations);
    }

    /** Resolve a troop's return type from its saved origin; legacy troops require a unique reverse mapping. */
    public static Map<String, Integer> back(String troopUnit, String armyUnit, Map<String, String> map) {
        if (troopUnit == null || troopUnit.isBlank() || map == null)
            throw new IllegalArgumentException("physical troop type and army map are required");
        if (armyUnit != null && !armyUnit.isBlank()) {
            if (!troopUnit.equals(map.get(armyUnit))) return Map.of();
            return Map.of(armyUnit, SCALE);
        }
        List<String> matches = map.entrySet().stream()
                .filter(entry -> troopUnit.equals(entry.getValue()))
                .map(Map.Entry::getKey).sorted().toList();
        if (matches.size() > 1)
            throw new IllegalStateException("Ambiguous strategic return mapping for physical troop "
                    + troopUnit + ": " + matches);
        return matches.isEmpty() ? Map.of() : Map.of(matches.get(0), SCALE);
    }

    /** Legacy aggregate helper; ambiguous reverse mappings fail closed instead of selecting an arbitrary type. */
    public static Map<String, Integer> back(Map<String, Integer> survivorsByTroop, Map<String, String> map) {
        Map<String, Integer> out = new LinkedHashMap<>();
        if (survivorsByTroop == null || map == null) throw new IllegalArgumentException("survivors and army map are required");
        for (var entry : survivorsByTroop.entrySet()) {
            if (entry.getValue() == null || entry.getValue() < 0)
                throw new IllegalArgumentException("invalid survivor count for " + entry.getKey());
            Map<String, Integer> resolved = back(entry.getKey(), "", map);
            if (entry.getValue() == 0 || resolved.isEmpty()) continue;
            String armyType = resolved.keySet().iterator().next();
            out.merge(armyType, Math.multiplyExact(entry.getValue(), SCALE), Math::addExact);
        }
        return out;
    }

    /** Содержание в день для набора солдат (тип -> число). */
    public static double upkeep(TroopDefs defs, Map<String, Integer> counts) {
        double c = 0;
        for (var e : counts.entrySet()) { TroopDefs.Unit u = defs.units.get(e.getKey()); if (u != null) c += u.upkeep() * e.getValue(); }
        return Math.round(c * 100) / 100.0;
    }

    /** Кто дезертирует, когда жалованье не выплачено: индексы солдат (случайно, по DESERT_CHANCE). */
    public static List<Integer> deserters(int count, Random rng) {
        List<Integer> l = new ArrayList<>();
        for (int i = 0; i < count; i++) if (rng.nextDouble() < DESERT_CHANCE) l.add(i);
        return l;
    }
}
