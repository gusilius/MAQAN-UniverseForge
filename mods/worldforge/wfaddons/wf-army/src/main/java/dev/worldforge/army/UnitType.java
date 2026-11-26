package dev.worldforge.army;

import dev.worldforge.war.War;

import java.util.*;

/** Тип отряда. Всё, что влияет на бой, задано данными (wf_war/units.json); значения по умолчанию - в {@link #defaults()}. */
public final class UnitType {
    public final String id, name;
    public final double attack, defense, speed, siege, cost, upkeep;
    public final boolean levy;                              // набирается из ополчения (не стоит золота, тратит запас людей)
    public final Map<String, Double> counters;               // против какого типа множитель урона

    public UnitType(String id, String name, double attack, double defense, double speed, double siege, double cost, double upkeep, boolean levy, Map<String, Double> counters) {
        this.id = id; this.name = name; this.attack = attack; this.defense = defense; this.speed = speed; this.siege = siege;
        this.cost = cost; this.upkeep = upkeep; this.levy = levy; this.counters = counters;
    }

    public double counter(String target) { return counters.getOrDefault(target, 1.0); }

    /** Камень-ножницы-бумага как в Total War: копья бьют конницу, конница - лучников, лучники - пехоту. */
    public static Map<String, UnitType> defaults() {
        Map<String, UnitType> m = new LinkedHashMap<>();
        add(m, new UnitType("levy", "Ополченцы", 1.0, 1.0, 1.0, 0, 0, 0.01, true, Map.of()));
        add(m, new UnitType("spear", "Копейщики", 1.1, 1.5, 1.0, 0, 0.8, 0.02, true, Map.of("cavalry", 1.8)));
        add(m, new UnitType("archer", "Лучники", 1.6, 0.6, 1.0, 0, 1.0, 0.025, false, Map.of("levy", 1.3, "spear", 1.3)));
        add(m, new UnitType("cavalry", "Конница", 2.4, 1.1, 2.0, 0, 2.5, 0.06, false, Map.of("archer", 1.8, "levy", 1.3)));
        add(m, new UnitType("knight", "Рыцари", 3.2, 2.0, 1.5, 0, 5.0, 0.12, false, Map.of("levy", 1.5, "archer", 1.5)));
        add(m, new UnitType("siege", "Осадные машины", 0.3, 0.3, 0.6, 1.0, 12.0, 0.2, false, Map.of()));
        return m;
    }

    private static void add(Map<String, UnitType> m, UnitType u) { m.put(u.id, u); }

    @SuppressWarnings("unchecked")
    public static Map<String, UnitType> parse(String json) {
        Map<String, UnitType> m = new LinkedHashMap<>();
        Object o = dev.worldforge.mod.Json.parse(json);
        Map<String, Object> root = (Map<String, Object>) ((Map<String, Object>) o).get("units");
        if (root == null) return m;
        for (Map.Entry<String, Object> e : root.entrySet()) {
            Map<String, Object> u = (Map<String, Object>) e.getValue();
            Map<String, Double> c = new LinkedHashMap<>();
            if (u.get("counters") instanceof Map<?, ?> cm) for (Map.Entry<?, ?> ce : cm.entrySet()) c.put(String.valueOf(ce.getKey()), ((Number) ce.getValue()).doubleValue());
            m.put(e.getKey(), new UnitType(e.getKey(), String.valueOf(u.getOrDefault("name", e.getKey())), num(u, "attack", 1), num(u, "defense", 1), num(u, "speed", 1), num(u, "siege", 0), num(u, "cost", 0), num(u, "upkeep", 0), Boolean.TRUE.equals(u.get("levy")), c));
        }
        return m;
    }

    private static double num(Map<String, Object> m, String k, double d) { return m.get(k) instanceof Number n ? n.doubleValue() : d; }
}
