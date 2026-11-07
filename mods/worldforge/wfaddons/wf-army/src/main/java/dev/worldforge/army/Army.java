package dev.worldforge.army;


import java.util.*;

/** Армия: отряды по типам, положение (баронство), путь и приказ. */
public final class Army {
    public final int id;
    public final String realm;
    public String name, commander = "";
    public int martial;                                       // военное умение командира (+3% силы за единицу)
    public int location;                                      // id титула-баронства
    public double morale = 1.0, moveAcc, supply = 1.0;
    public boolean mercenary;
    public final Map<String, Integer> units = new LinkedHashMap<>();
    public final List<Integer> path = new ArrayList<>();      // следующие шаги (id баронств)
    public int siegeTitle;                                    // осаждаемое баронство, 0 - нет
    public String lastEvent = "";

    Army(int id, String realm, String name, int location) { this.id = id; this.realm = realm; this.name = name; this.location = location; }

    public int men() { int n = 0; for (int v : units.values()) n += v; return n; }

    public int count(String type) { return units.getOrDefault(type, 0); }

    public void add(String type, int n) { if (n > 0) units.merge(type, n, Integer::sum); }

    public boolean moving() { return !path.isEmpty(); }

    /** Ударная сила по типам (для показа и ИИ). */
    public double power(Map<String, UnitType> types) {
        double p = 0;
        for (Map.Entry<String, Integer> e : units.entrySet()) { UnitType t = types.get(e.getKey()); if (t != null) p += e.getValue() * (t.attack + t.defense) / 2; }
        return p * (1 + 0.03 * martial) * (0.5 + 0.5 * morale);
    }

    public double speed(Map<String, UnitType> types) {
        double s = 99;
        for (Map.Entry<String, Integer> e : units.entrySet()) { UnitType t = types.get(e.getKey()); if (t != null && e.getValue() > 0) s = Math.min(s, t.speed); }
        return s == 99 ? 1 : s;
    }
}
