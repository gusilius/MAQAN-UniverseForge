package dev.worldforge.troops;

import dev.worldforge.mod.Json;

import java.util.*;

/**
 * Типы солдат из JSON (секция "units"). role: melee, ranged, cavalry, support. Один солдат в мире - один человек.
 * hp, dmg, armor - в единицах Майнкрафта; range - дальность стрельбы или удара (блоки), speed - множитель скорости, sight - радиус обзора (блоки),
 * cost - золото за найм, upkeep - содержание в день. weapon, head, chest - предметы, которые солдат держит и носит; skin - вид (steve, alex, zombie...).
 */
public final class TroopDefs {
    public record Unit(String id, String name, String role, double hp, double dmg, double range, double speed, double armor, double sight,
                       int cost, double upkeep, String weapon, String head, String chest, String legs, String feet, String desc, double aura, double heal, double siege) {}

    public static final Set<String> ROLES = Set.of("melee", "ranged", "cavalry", "support", "siege");
    public final Map<String, Unit> units = new LinkedHashMap<>();
    /** Соответствие типов армии wf-army типам солдат (секция "army_map"); пусто - Muster.defaultMap(). */
    public final Map<String, String> armyMap = new LinkedHashMap<>();

    private static double dbl(Object o, double d) { return o instanceof Number n ? n.doubleValue() : d; }
    private static String str(Object o, String d) { return o == null ? d : String.valueOf(o); }

    @SuppressWarnings("unchecked")
    public void load(String json) {
        Object root = Json.parse(json);
        if (!(root instanceof Map)) throw new IllegalArgumentException("Нужен объект JSON");
        if (((Map<String, Object>) root).get("units") instanceof List<?> l) for (Object o : l) add((Map<String, Object>) o);
        if (((Map<String, Object>) root).get("army_map") instanceof Map<?, ?> am) for (var e : am.entrySet()) armyMap.put(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
    }

    public Map<String, String> armyTypes() { return armyMap.isEmpty() ? Muster.defaultMap() : armyMap; }

    public void add(Map<String, Object> u) {
        String id = str(u.get("id"), "");
        if (id.isEmpty()) throw new IllegalArgumentException("Тип солдата без id");
        units.put(id, new Unit(id, str(u.get("name"), id), str(u.get("role"), "melee"), dbl(u.get("hp"), 20), dbl(u.get("dmg"), 3), dbl(u.get("range"), 2), dbl(u.get("speed"), 1),
                dbl(u.get("armor"), 0), dbl(u.get("sight"), 24), (int) dbl(u.get("cost"), 10), dbl(u.get("upkeep"), 0.2),
                str(u.get("weapon"), ""), str(u.get("head"), ""), str(u.get("chest"), ""), str(u.get("legs"), ""), str(u.get("feet"), ""), str(u.get("desc"), ""), dbl(u.get("aura"), 0), dbl(u.get("heal"), 0), dbl(u.get("siege"), 0)));
    }

    public List<String> validate() {
        List<String> e = new ArrayList<>();
        for (Unit u : units.values()) {
            if (!ROLES.contains(u.role())) e.add(u.id() + ": неизвестная роль " + u.role());
            if (u.hp() <= 0) e.add(u.id() + ": здоровье должно быть больше нуля");
            if (u.speed() <= 0 || u.speed() > 3) e.add(u.id() + ": скорость от 0 до 3");
            if (u.role().equals("ranged") && u.range() < 6) e.add(u.id() + ": у стрелка дальность меньше 6");
            if (u.cost() < 0) e.add(u.id() + ": цена не может быть отрицательной");
        }
        if (units.isEmpty()) e.add("нет типов солдат");
        for (var m : armyMap.entrySet()) if (!units.containsKey(m.getValue())) e.add("army_map: нет солдата " + m.getValue() + " для " + m.getKey());
        return e;
    }
}
