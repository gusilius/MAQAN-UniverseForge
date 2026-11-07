package dev.worldforge.reputation;

import dev.worldforge.mod.Json;

import java.util.*;

/**
 * Отношение игроков к государствам, домам и фракциям. Ключ цели - строка вида "realm:stark", "house:lannister", "faction:guild".
 * Значения от -100 до 100. Фракции связаны: отношение к дому отчасти переходит на его государство (см. {@link #link}).
 * Не зависит от Minecraft; хозяин мода сохраняет {@link #toJson()} вместе с миром.
 */
public final class ReputationStore {
    public static final int MIN = -100, MAX = 100, RIPPLE_DEPTH = 3;

    @FunctionalInterface
    public interface Listener {
        void changed(String player, String target, int oldValue, int newValue, String reason);
    }

    private static final class Link {
        final String parent;
        final double factor;

        Link(String parent, double factor) { this.parent = parent; this.factor = factor; }
    }

    private final Map<String, Map<String, Integer>> values = new TreeMap<>();
    private final Map<String, Link> links = new HashMap<>();
    private final Map<String, String> names = new HashMap<>();
    private final List<Listener> listeners = new ArrayList<>();

    public void addListener(Listener l) { listeners.add(l); }

    /** Связь child -> parent: доля изменения (0..1), которая переходит на родителя. Циклы запрещены. */
    public void link(String child, String parent, double factor) {
        if (factor < 0 || factor > 1) throw new IllegalArgumentException("Доля должна быть от 0 до 1");
        for (String c = parent; c != null; c = links.containsKey(c) ? links.get(c).parent : null) {
            if (c.equals(child)) throw new IllegalArgumentException("Цикл связей: " + child + " <-> " + parent);
        }
        links.put(child, new Link(parent, factor));
    }

    public void name(String target, String name) { names.put(target, name); }

    public String nameOf(String target) { return names.getOrDefault(target, target); }

    /** Загружает фракции: {"factions":[{"id":"house:stark","name":"Старк","parent":"realm:north","factor":0.5}]}. */
    @SuppressWarnings("unchecked")
    public void loadFactions(String json) {
        Object root = Json.parse(json);
        if (!(root instanceof Map) || !(((Map<String, Object>) root).get("factions") instanceof List)) throw new IllegalArgumentException("Нужен список factions");
        for (Object o : (List<Object>) ((Map<String, Object>) root).get("factions")) {
            Map<String, Object> f = (Map<String, Object>) o;
            String id = String.valueOf(f.get("id"));
            if (f.get("name") != null) name(id, String.valueOf(f.get("name")));
            if (f.get("parent") != null) link(id, String.valueOf(f.get("parent")), f.get("factor") instanceof Number n ? n.doubleValue() : 0.5);
        }
    }

    public int get(String player, String target) {
        Map<String, Integer> m = values.get(player);
        return m == null ? 0 : m.getOrDefault(target, 0);
    }

    public Standing standing(String player, String target) { return Standing.of(get(player, target)); }

    public Map<String, Integer> all(String player) {
        Map<String, Integer> m = values.get(player);
        return m == null ? Map.of() : new TreeMap<>(m);
    }

    public void set(String player, String target, int value, String reason) {
        int nv = Math.max(MIN, Math.min(MAX, value)), old = get(player, target);
        if (nv == old) return;
        if (nv == 0) {
            values.get(player).remove(target);
            if (values.get(player).isEmpty()) values.remove(player);
        } else values.computeIfAbsent(player, k -> new TreeMap<>()).put(target, nv);
        for (Listener l : listeners) l.changed(player, target, old, nv, reason == null ? "" : reason);
    }

    /** Меняет отношение и передаёт долю изменения родителям (округляя к нулю, мелочь меньше 1 пропадает). */
    public void add(String player, String target, int delta, String reason) {
        set(player, target, get(player, target) + delta, reason);
        double d = delta;
        String cur = target;
        for (int depth = 0; depth < RIPPLE_DEPTH && links.containsKey(cur); depth++) {
            Link l = links.get(cur);
            d *= l.factor;
            int step = (int) d;                        // к нулю
            if (step == 0) break;
            set(player, l.parent, get(player, l.parent) + step, reason);
            cur = l.parent;
        }
    }

    /** Все отношения игрока сдвигаются к нулю на points (слухи забываются). */
    public void decay(int points) {
        for (String p : new ArrayList<>(values.keySet())) {
            for (Map.Entry<String, Integer> e : new ArrayList<>(values.get(p).entrySet())) {
                int v = e.getValue(), nv = v > 0 ? Math.max(0, v - points) : Math.min(0, v + points);
                set(p, e.getKey(), nv, "забвение");
            }
        }
    }

    public String toJson() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("format", "wf-reputation/1");
        root.put("values", values);
        return Json.write(root);
    }

    @SuppressWarnings("unchecked")
    public void fromJson(String json) {
        values.clear();
        Object root = Json.parse(json);
        if (!(root instanceof Map) || !(((Map<String, Object>) root).get("values") instanceof Map)) throw new IllegalArgumentException("Неверный формат репутации");
        for (Map.Entry<String, Object> e : ((Map<String, Object>) ((Map<String, Object>) root).get("values")).entrySet()) {
            Map<String, Integer> m = new TreeMap<>();
            for (Map.Entry<String, Object> t : ((Map<String, Object>) e.getValue()).entrySet()) {
                int v = ((Number) t.getValue()).intValue();
                if (v != 0) m.put(t.getKey(), Math.max(MIN, Math.min(MAX, v)));
            }
            if (!m.isEmpty()) values.put(e.getKey(), m);
        }
    }
}
