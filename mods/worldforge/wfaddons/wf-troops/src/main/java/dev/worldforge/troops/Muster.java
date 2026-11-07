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

    public record Plan(Map<String, Integer> soldiers, Map<String, Integer> menTaken) {
        public int total() { return soldiers.values().stream().mapToInt(Integer::intValue).sum(); }
    }

    /** Сколько и каких солдат можно призвать из армии (maxSoldiers - сколько хочет игрок). Остаток меньше SCALE человек не берётся. Осадные машины игнорируются. */
    public static Plan plan(Map<String, Integer> armyUnits, int maxSoldiers, Map<String, String> map) {
        Map<String, Integer> soldiers = new LinkedHashMap<>(), taken = new LinkedHashMap<>();
        int left = maxSoldiers;
        for (var e : armyUnits.entrySet()) {
            String troop = map.get(e.getKey());
            if (troop == null || left <= 0) continue;
            int can = Math.min(left, e.getValue() / SCALE);
            if (can <= 0) continue;
            soldiers.merge(troop, can, Integer::sum);
            taken.merge(e.getKey(), can * SCALE, Integer::sum);
            left -= can;
        }
        return new Plan(soldiers, taken);
    }

    /** Выжившие возвращаются в армию: тип солдата -> число людей по типам армии. */
    public static Map<String, Integer> back(Map<String, Integer> survivorsByTroop, Map<String, String> map) {
        Map<String, String> rev = new HashMap<>();
        for (var e : map.entrySet()) rev.putIfAbsent(e.getValue(), e.getKey());
        Map<String, Integer> out = new LinkedHashMap<>();
        for (var e : survivorsByTroop.entrySet()) {
            String a = rev.get(e.getKey());
            if (a != null) out.merge(a, e.getValue() * SCALE, Integer::sum);
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
