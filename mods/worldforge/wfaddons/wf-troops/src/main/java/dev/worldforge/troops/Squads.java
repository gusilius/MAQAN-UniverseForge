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
        if (owner == null || owner.isBlank()) throw new IllegalArgumentException("owner is required");
        if (members == null) throw new IllegalArgumentException("members are required");
        int id = counter++;
        Squad s = new Squad("s" + id, name == null || name.isBlank() ? "Отряд " + id : name, owner);
        Set<String> unique = new LinkedHashSet<>();
        for (String member : members) {
            if (member == null || member.isBlank() || !unique.add(member) || s.members.size() >= MAX_SIZE) continue;
            remove(member);
            s.members.add(member);
        }
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
        if (from == null || units == null || !squads.containsKey(from.id)) return null;
        int oldCurrent = from.members.size(), oldInitial = Math.max(oldCurrent, from.initial);
        double oldMorale = from.morale;
        Squad n = create(from.owner, from.name + " (часть)", units);
        int moved = n.members.size();
        if (moved == 0) { squads.remove(n.id); return null; }
        int movedInitial = Math.max(moved, (int) Math.round(oldInitial * moved / (double) Math.max(1, oldCurrent)));
        n.initial = movedInitial;
        from.initial = Math.max(from.members.size(), oldInitial - movedInitial);
        n.morale = oldMorale; n.routed = from.routed; n.realm = from.realm;
        n.formation = from.formation; n.stance = from.stance; n.cx = from.cx; n.cz = from.cz; n.yaw = from.yaw;
        return n;
    }

    public boolean merge(String aId, String bId) {
        Squad a = squads.get(aId), b = squads.get(bId);
        if (a == null || b == null || a == b || !a.owner.equals(b.owner) || a.members.size() >= MAX_SIZE) return false;
        int oldASize = a.members.size(), oldBSize = b.members.size();
        int oldBInitial = Math.max(oldBSize, b.initial);
        double oldMorale = a.morale;
        int moved = 0;
        for (String member : new ArrayList<>(b.members)) {
            if (a.members.size() >= MAX_SIZE) break;
            b.members.remove(member);
            a.members.add(member);
            moved++;
        }
        if (moved == 0) return false;
        int movedInitial = Math.max(moved, (int) Math.round(oldBInitial * moved / (double) Math.max(1, oldBSize)));
        a.initial = Math.min(1_000_000, Math.max(oldASize + moved, a.initial) + movedInitial);
        b.initial = Math.max(b.members.size(), oldBInitial - movedInitial);
        a.morale = (oldMorale * oldASize + b.morale * moved) / Math.max(1, oldASize + moved);
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

    private static String jsonString(String value) {
        if (value == null) value = "";
        StringBuilder out = new StringBuilder(value.length() + 2).append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
                }
            }
        }
        return out.append('"').toString();
    }

    public String viewJson(String owner) {
        StringBuilder b = new StringBuilder("[");
        boolean first = true;
        for (Squad s : of(owner)) {
            if (!first) b.append(',');
            first = false;
            Order o = s.current();
            b.append("{\"id\":").append(jsonString(s.id))
                    .append(",\"name\":").append(jsonString(s.name))
                    .append(",\"n\":").append(s.members.size())
                    .append(",\"formation\":").append(jsonString(s.formation.title))
                    .append(",\"stance\":").append(jsonString(s.stance.title))
                    .append(",\"order\":").append(jsonString(o == null ? "Ожидает" : o.kind().title)).append('}');
        }
        return b.append(']').toString();
    }

    // ---- сохранение ----
    public String toJson() {
        StringBuilder b = new StringBuilder("{\"v\":\"wf-squads/1\",\"counter\":")
                .append(Math.max(1, counter)).append(",\"squads\":[");
        boolean first = true;
        for (Squad s : squads.values()) {
            if (s == null) continue;
            if (!first) b.append(',');
            first = false;
            b.append("{\"id\":").append(jsonString(s.id))
                    .append(",\"name\":").append(jsonString(s.name))
                    .append(",\"owner\":").append(jsonString(s.owner))
                    .append(",\"realm\":").append(jsonString(s.realm))
                    .append(",\"formation\":").append(jsonString(s.formation.name()))
                    .append(",\"stance\":").append(jsonString(s.stance.name()))
                    .append(",\"initial\":").append(Math.max(s.members.size(), s.initial))
                    .append(",\"morale\":").append(Double.isFinite(s.morale) ? Math.max(0, Math.min(1, s.morale)) : 1.0)
                    .append(",\"routed\":").append(s.routed)
                    .append(",\"cx\":").append(Double.isFinite(s.cx) ? s.cx : 0)
                    .append(",\"cz\":").append(Double.isFinite(s.cz) ? s.cz : 0)
                    .append(",\"yaw\":").append(Double.isFinite(s.yaw) ? s.yaw : 0)
                    .append(",\"queue\":[");
            boolean firstOrder = true;
            for (Order order : s.queue) {
                if (order == null || order.kind() == Kind.STOP) continue;
                if (!firstOrder) b.append(',');
                firstOrder = false;
                b.append("{\"kind\":").append(jsonString(order.kind().name()))
                        .append(",\"x\":").append(Double.isFinite(order.x()) ? order.x() : 0)
                        .append(",\"z\":").append(Double.isFinite(order.z()) ? order.z() : 0)
                        .append(",\"target\":").append(jsonString(order.target())).append('}');
            }
            b.append("],\"members\":[");
            for (int i = 0; i < s.members.size(); i++) {
                if (i > 0) b.append(',');
                b.append(jsonString(s.members.get(i)));
            }
            b.append("]}");
        }
        return b.append("]}").toString();
    }

    /** Повреждённые записи пропускаются; одна плохая запись не уничтожает все сохранённые отряды. */
    @SuppressWarnings("unchecked")
    public void fromJson(String json) {
        squads.clear();
        counter = 1;
        Object root;
        try {
            root = dev.worldforge.mod.Json.parse(json == null ? "" : json);
        } catch (RuntimeException ex) {
            return;
        }
        if (!(root instanceof Map<?, ?> raw)) return;
        Object rawCounter = raw.get("counter");
        if (rawCounter instanceof Number n && n.doubleValue() >= 1 && n.doubleValue() <= Integer.MAX_VALUE)
            counter = Math.max(1, n.intValue());
        if (!(raw.get("squads") instanceof List<?> entries)) return;

        Set<String> seenIds = new HashSet<>();
        Set<String> seenMembers = new HashSet<>();
        for (Object value : entries) {
            if (!(value instanceof Map<?, ?> e)) continue;
            String id = e.get("id") instanceof String v ? v.trim() : "";
            String owner = e.get("owner") instanceof String v ? v.trim() : "";
            if (id.isEmpty() || owner.isEmpty() || !seenIds.add(id)) continue;
            String name = e.get("name") instanceof String v && !v.isBlank() ? v : "Отряд";
            Squad s = new Squad(id, name, owner);
            s.realm = e.get("realm") instanceof String v ? v : "";
            try { s.formation = Formations.Type.valueOf(String.valueOf(e.get("formation"))); } catch (RuntimeException ignored) {}
            try { s.stance = Stance.valueOf(String.valueOf(e.get("stance"))); } catch (RuntimeException ignored) {}
            s.cx = finiteNumber(e.get("cx"), 0);
            s.cz = finiteNumber(e.get("cz"), 0);
            s.yaw = finiteNumber(e.get("yaw"), 0);
            s.morale = Math.max(0, Math.min(1, finiteNumber(e.get("morale"), 1)));
            s.routed = Boolean.TRUE.equals(e.get("routed"));
            if (e.get("queue") instanceof List<?> orders) {
                for (Object rawOrder : orders) {
                    if (s.queue.size() >= 64 || !(rawOrder instanceof Map<?, ?> orderData)) break;
                    Kind kind;
                    try { kind = Kind.valueOf(String.valueOf(orderData.get("kind"))); }
                    catch (RuntimeException ignored) { continue; }
                    if (kind == Kind.STOP) continue;
                    double x = finiteNumber(orderData.get("x"), 0);
                    double z = finiteNumber(orderData.get("z"), 0);
                    String target = orderData.get("target") instanceof String v ? v : "";
                    s.queue.addLast(new Order(kind, x, z, target));
                }
            }
            if (e.get("members") instanceof List<?> members) {
                for (Object member : members) {
                    if (!(member instanceof String unit) || unit.isBlank() || !seenMembers.add(unit)) continue;
                    if (s.members.size() >= MAX_SIZE) break;
                    s.members.add(unit);
                }
            }
            if (s.members.isEmpty()) continue;
            int requestedInitial = e.get("initial") instanceof Number n
                    ? Math.max(0, Math.min(1_000_000, n.intValue())) : s.members.size();
            s.initial = Math.max(s.members.size(), requestedInitial);
            squads.put(s.id, s);
            if (id.matches("s[0-9]+")) {
                try { counter = Math.max(counter, Math.addExact(Integer.parseInt(id.substring(1)), 1)); }
                catch (RuntimeException ignored) {}
            }
        }
    }

    private static double finiteNumber(Object value, double fallback) {
        if (!(value instanceof Number n)) return fallback;
        double result = n.doubleValue();
        return Double.isFinite(result) ? result : fallback;
    }
}
