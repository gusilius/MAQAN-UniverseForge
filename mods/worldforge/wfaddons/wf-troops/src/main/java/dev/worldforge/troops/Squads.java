package dev.worldforge.troops;

import java.util.*;

/** Отряды и приказы. Чистая логика: игровые сущности адресуются строками UUID. */
public final class Squads {
    public enum Kind { MOVE("Идти"), ATTACK_MOVE("Атаковать по пути"), ATTACK("Атаковать цель"), HOLD("Держать позицию"), PATROL("Патруль"), FOLLOW("Следовать"), GUARD("Охранять"), RETREAT("Отступить"), STOP("Стоп");
        public final String title; Kind(String t) { title = t; } }
    public enum Stance { AGGRESSIVE("Агрессивно"), DEFENSIVE("Оборонительно"), HOLD("Не двигаться"), PASSIVE("Не драться");
        public final String title; Stance(String t) { title = t; } public Stance next() { return values()[(ordinal() + 1) % values().length]; } }

    public record Order(Kind kind, double x, double z, String target) {
        public static Order move(double x, double z) { return new Order(Kind.MOVE, x, z, ""); }
        public static Order of(Kind k) { return new Order(k, 0, 0, ""); }
    }

    public static final class Squad {
        public final String id; public String name; public final String owner; public String realm = "";
        public final List<String> members = new ArrayList<>();
        public Formations.Type formation = Formations.Type.BLOCK; public Stance stance = Stance.DEFENSIVE;
        public final Deque<Order> queue = new ArrayDeque<>();
        public double cx, cz, yaw;
        public int initial;                      // начальный состав: от него считаются потери для морали
        public double morale = 1.0;
        public boolean routed;
        public int lastAlive = -1;               // для шага морали (не сохраняется)
        Squad(String id, String name, String owner) { this.id = id; this.name = name; this.owner = owner; }
        public Order current() { return queue.peekFirst(); }
    }

    public static final int MAX_SIZE = 40;
    private final Map<String, Squad> squads = new LinkedHashMap<>();
    private int counter = 1;

    public Squad create(String owner, String name, Collection<String> members) {
        Squad s = new Squad("s" + counter++, name == null || name.isBlank() ? "Отряд " + (counter - 1) : name, owner);
        for (String m : members) { remove(m); if (s.members.size() < MAX_SIZE) s.members.add(m); }
        s.initial = s.members.size();
        squads.put(s.id, s);
        return s;
    }

    public Squad get(String id) { return squads.get(id); }
    public Collection<Squad> all() { return squads.values(); }
    public List<Squad> of(String owner) { List<Squad> l = new ArrayList<>(); for (Squad s : squads.values()) if (s.owner.equals(owner)) l.add(s); return l; }

    public Squad squadOf(String unit) { for (Squad s : squads.values()) if (s.members.contains(unit)) return s; return null; }

    /** Убирает солдата из его отряда (смерть, роспуск). Пустой отряд исчезает. */
    public void remove(String unit) {
        Squad s = squadOf(unit);
        if (s == null) return;
        s.members.remove(unit);
        if (s.members.isEmpty()) squads.remove(s.id);
    }

    public void command(String squadId, Order o, boolean queue) {
        Squad s = squads.get(squadId);
        if (s == null) return;
        if (o.kind() == Kind.STOP) { s.queue.clear(); return; }
        if (!queue) s.queue.clear();
        s.queue.addLast(o);
    }

    /**
     * Приказ части солдат: если выбраны все члены отряда - приказ отряду; иначе выбранные выделяются в новый отряд (как в Total War).
     * Возвращает отряд-получатель.
     */
    public Squad commandUnits(String owner, Collection<String> units, Order o, boolean queue) {
        Set<String> sel = new LinkedHashSet<>();
        for (String u : units) { Squad s = squadOf(u); if (s != null && s.owner.equals(owner)) sel.add(u); }
        if (sel.isEmpty()) return null;
        Map<Squad, List<String>> by = new LinkedHashMap<>();
        for (String u : sel) by.computeIfAbsent(squadOf(u), k -> new ArrayList<>()).add(u);
        Squad target;
        if (by.size() == 1) {
            Squad only = by.keySet().iterator().next();
            if (by.get(only).size() == only.members.size()) target = only;
            else { target = split(only, by.get(only)); }
        } else {
            target = create(owner, "Сводный отряд", sel);
        }
        command(target.id, o, queue);
        return target;
    }

    public Squad split(Squad from, Collection<String> units) {
        Squad n = create(from.owner, from.name + " (часть)", units);
        from.initial = Math.max(from.members.size(), from.initial - n.members.size()); n.morale = from.morale; n.routed = from.routed; n.realm = from.realm; n.formation = from.formation; n.stance = from.stance; n.cx = from.cx; n.cz = from.cz; n.yaw = from.yaw;
        return n;
    }

    public boolean merge(String aId, String bId) {
        Squad a = squads.get(aId), b = squads.get(bId);
        if (a == null || b == null || a == b || !a.owner.equals(b.owner)) return false;
        for (String m : new ArrayList<>(b.members)) if (a.members.size() < MAX_SIZE) { b.members.remove(m); a.members.add(m); }
        a.initial = Math.min(MAX_SIZE, a.initial + b.initial);
        a.morale = Math.min(a.morale, b.morale);
        if (b.members.isEmpty()) squads.remove(b.id);
        return true;
    }

    public void disband(String id) { squads.remove(id); }

    /** Шаг приказа: подтвердить выполнение (достигли точки / цель мертва) и перейти к следующему. */
    public void completeCurrent(String squadId) {
        Squad s = squads.get(squadId);
        if (s == null || s.queue.isEmpty()) return;
        Order o = s.queue.pollFirst();
        if (o.kind() == Kind.PATROL) s.queue.addLast(new Order(Kind.PATROL, s.cx, s.cz, ""));     // патруль зацикливается: вернуться на старт
    }

    public String viewJson(String owner) {
        StringBuilder b = new StringBuilder("[");
        boolean first = true;
        for (Squad s : of(owner)) {
            if (!first) b.append(',');
            first = false;
            Order o = s.current();
            b.append("{\"id\":\"").append(s.id).append("\",\"name\":\"").append(s.name.replace("\"", "'")).append("\",\"n\":").append(s.members.size())
                    .append(",\"formation\":\"").append(s.formation.title).append("\",\"stance\":\"").append(s.stance.title).append("\",\"order\":\"")
                    .append(o == null ? "Ожидает" : o.kind().title).append("\"}");
        }
        return b.append(']').toString();
    }

    // ---- сохранение ----
    public String toJson() {
        StringBuilder b = new StringBuilder("{\"v\":\"wf-squads/1\",\"counter\":").append(counter).append(",\"squads\":[");
        boolean first = true;
        for (Squad s : squads.values()) {
            if (!first) b.append(',');
            first = false;
            b.append("{\"id\":\"").append(s.id).append("\",\"name\":\"").append(s.name.replace("\\", "").replace("\"", "'")).append("\",\"owner\":\"").append(s.owner)
                    .append("\",\"realm\":\"").append(s.realm).append("\",\"formation\":\"").append(s.formation).append("\",\"stance\":\"").append(s.stance)
                    .append("\",\"initial\":").append(s.initial).append(",\"morale\":").append(s.morale).append(",\"routed\":").append(s.routed).append(",\"cx\":").append(s.cx).append(",\"cz\":").append(s.cz).append(",\"yaw\":").append(s.yaw).append(",\"members\":[");
            for (int i = 0; i < s.members.size(); i++) b.append(i > 0 ? "," : "").append('"').append(s.members.get(i)).append('"');
            b.append("]}");
        }
        return b.append("]}").toString();
    }

    @SuppressWarnings("unchecked")
    public void fromJson(String json) {
        squads.clear();
        Object root = dev.worldforge.mod.Json.parse(json);
        if (!(root instanceof Map)) return;
        Map<String, Object> m = (Map<String, Object>) root;
        counter = m.get("counter") instanceof Number n ? n.intValue() : 1;
        if (m.get("squads") instanceof List<?> l) for (Object o : l) {
            Map<String, Object> e = (Map<String, Object>) o;
            Squad s = new Squad(String.valueOf(e.get("id")), String.valueOf(e.get("name")), String.valueOf(e.get("owner")));
            s.realm = String.valueOf(e.getOrDefault("realm", ""));
            try { s.formation = Formations.Type.valueOf(String.valueOf(e.get("formation"))); } catch (Exception ignored) {}
            try { s.stance = Stance.valueOf(String.valueOf(e.get("stance"))); } catch (Exception ignored) {}
            s.cx = e.get("cx") instanceof Number n ? n.doubleValue() : 0; s.cz = e.get("cz") instanceof Number n ? n.doubleValue() : 0; s.yaw = e.get("yaw") instanceof Number n ? n.doubleValue() : 0;
            s.initial = e.get("initial") instanceof Number n ? n.intValue() : 0;
            s.morale = e.get("morale") instanceof Number n ? n.doubleValue() : 1.0;
            s.routed = Boolean.TRUE.equals(e.get("routed"));
            if (e.get("members") instanceof List<?> ms) for (Object x : ms) s.members.add(String.valueOf(x));
            if (s.initial < s.members.size()) s.initial = s.members.size();
            squads.put(s.id, s);
        }
    }
}
