package dev.worldforge.quests;

import dev.worldforge.mod.Json;

import java.util.*;

/**
 * Задания и события мира из JSON (секции "quests" и "events"; несколько файлов объединяются, одинаковый id заменяется).
 * Задание: кто даёт (giver), требования (requires), параметры с жеребьёвкой (params), шаги (steps) и исходы (outcomes).
 * Шаги: kill, fetch, talk, reach, action, level, flag, join_org, wait, choice. Событие: текст, варианты решения, последствия.
 */
public final class QuestDefs {
    public record Option(String id, String text, String next, Map<String, Object> requires, Map<String, Object> effects) {}
    public record Step(String id, String type, String text, String next, String end, Map<String, Object> raw, List<Option> options) {
        public String str(String k, String d) { Object o = raw.get(k); return o == null ? d : String.valueOf(o); }
        public int num(String k, int d) { Object o = raw.get(k); return o instanceof Number n ? n.intValue() : o == null ? d : parse(String.valueOf(o), d); }
    }
    public record Quest(String id, String name, String desc, int tier, List<String> giver, int minLevel, int maxLevel, int repeat, int weight, int deadline,
                        Map<String, Object> requires, Map<String, Object> params, List<Step> steps, Map<String, Map<String, Object>> outcomes, String author) {
        public Step step(String id) { for (Step s : steps) if (s.id().equals(id)) return s; return null; }
    }
    public record EventChoice(String id, String text, Map<String, Object> requires, Map<String, Object> effects, int ai) {}
    public record Event(String id, String name, String scope, String text, int weight, int cooldown, int expire, Map<String, Object> requires,
                        List<EventChoice> choices, String dflt, String board) {}

    public static final Set<String> STEP_TYPES = Set.of("kill", "fetch", "talk", "reach", "action", "level", "flag", "join_org", "wait", "choice");
    public static final Set<String> EFFECTS = Set.of("gold", "xp", "items", "rep", "flags", "unflags", "rights", "relation", "news", "action", "org_influence", "treasury", "market", "prop", "quest", "follow");

    public final Map<String, Quest> quests = new LinkedHashMap<>();
    public final Map<String, Event> events = new LinkedHashMap<>();

    static int parse(String s, int d) { try { return (int) Double.parseDouble(s.trim()); } catch (RuntimeException e) { return d; } }
    private static int num(Object o, int d) { return o instanceof Number n ? n.intValue() : d; }
    private static String str(Object o, String d) { return o == null ? d : String.valueOf(o); }
    @SuppressWarnings("unchecked") private static Map<String, Object> map(Object o) { return o instanceof Map ? (Map<String, Object>) o : new LinkedHashMap<>(); }
    private static List<String> strs(Object o) { List<String> l = new ArrayList<>(); if (o instanceof List<?> x) for (Object e : x) l.add(String.valueOf(e)); else if (o instanceof String s) l.add(s); return l; }

    @SuppressWarnings("unchecked")
    public void load(String json) { load(json, null); }

    @SuppressWarnings("unchecked")
    public void load(String json, String author) {
        Object root = Json.parse(json);
        if (!(root instanceof Map)) throw new IllegalArgumentException("Нужен объект JSON");
        Map<String, Object> m = (Map<String, Object>) root;
        if (m.get("quests") instanceof List<?> l) for (Object o : l) { Quest q = quest((Map<String, Object>) o, author); quests.put(q.id(), q); }
        if (m.get("events") instanceof List<?> l) for (Object o : l) { Event e = event((Map<String, Object>) o); events.put(e.id(), e); }
        if (m.get("id") instanceof String && m.get("steps") instanceof List) { Quest q = quest(m, author); quests.put(q.id(), q); }   // одиночное задание
    }

    @SuppressWarnings("unchecked")
    public static Quest quest(Map<String, Object> m, String author) {
        if (!(m.get("id") instanceof String id) || !(m.get("name") instanceof String name)) throw new IllegalArgumentException("Задание: нужны id и name");
        List<Step> steps = new ArrayList<>();
        if (m.get("steps") instanceof List<?> l) for (Object o : l) {
            Map<String, Object> s = (Map<String, Object>) o;
            List<Option> opts = new ArrayList<>();
            if (s.get("options") instanceof List<?> ol) for (Object x : ol) {
                Map<String, Object> om = (Map<String, Object>) x;
                opts.add(new Option(str(om.get("id"), "o" + opts.size()), str(om.get("text"), "Вариант"), str(om.get("next"), ""), map(om.get("requires")), map(om.get("effects"))));
            }
            steps.add(new Step(str(s.get("id"), "s" + steps.size()), str(s.get("type"), "talk"), str(s.get("text"), ""), str(s.get("next"), ""), str(s.get("end"), ""), s, opts));
        }
        Map<String, Map<String, Object>> outs = new LinkedHashMap<>();
        if (m.get("outcomes") instanceof Map<?, ?> om) for (var e : om.entrySet()) outs.put(String.valueOf(e.getKey()), map(e.getValue()));
        if (!outs.containsKey("success")) outs.put("success", map(m.get("reward")));
        return new Quest(id, name, str(m.get("desc"), ""), num(m.get("tier"), 1), strs(m.get("giver")).isEmpty() ? List.of("any") : strs(m.get("giver")),
                num(m.get("min_level"), 1), num(m.get("max_level"), 999), num(m.get("repeat"), 0), Math.max(1, num(m.get("weight"), 3)), num(m.get("deadline"), 0),
                map(m.get("requires")), map(m.get("params")), steps, outs, author);
    }

    @SuppressWarnings("unchecked")
    public static Event event(Map<String, Object> m) {
        if (!(m.get("id") instanceof String id) || !(m.get("name") instanceof String name)) throw new IllegalArgumentException("Событие: нужны id и name");
        List<EventChoice> ch = new ArrayList<>();
        if (m.get("choices") instanceof List<?> l) for (Object o : l) {
            Map<String, Object> c = (Map<String, Object>) o;
            ch.add(new EventChoice(str(c.get("id"), "c" + ch.size()), str(c.get("text"), "Вариант"), map(c.get("requires")), map(c.get("effects")), Math.max(0, num(c.get("ai"), 1))));
        }
        return new Event(id, name, str(m.get("scope"), "realm"), str(m.get("text"), ""), Math.max(1, num(m.get("weight"), 3)), num(m.get("cooldown"), 20), num(m.get("expire"), 6),
                map(m.get("requires")), ch, str(m.get("default"), ch.isEmpty() ? "" : ch.get(0).id()), str(m.get("board"), ""));
    }

    /** Ошибки набора: неизвестные шаги, эффекты и ссылки. */
    public List<String> validate() {
        List<String> p = new ArrayList<>();
        for (Quest q : quests.values()) p.addAll(validate(q));
        for (Event e : events.values()) {
            if (e.choices().isEmpty()) p.add("событие " + e.id() + ": нет вариантов");
            if (!e.choices().isEmpty() && e.choices().stream().noneMatch(c -> c.id().equals(e.dflt()))) p.add("событие " + e.id() + ": default не среди вариантов");
            for (EventChoice c : e.choices()) for (String k : c.effects().keySet()) if (!EFFECTS.contains(k)) p.add("событие " + e.id() + ": неизвестный эффект '" + k + "'");
            if (!e.board().isEmpty() && !quests.containsKey(e.board())) p.add("событие " + e.id() + ": board ссылается на неизвестное задание '" + e.board() + "'");
            for (EventChoice c : e.choices()) if (c.effects().get("quest") instanceof String qid && !quests.containsKey(qid)) p.add("событие " + e.id() + ": quest '" + qid + "' не найден");
        }
        return p;
    }

    public List<String> validate(Quest q) {
        List<String> p = new ArrayList<>();
        if (q.steps().isEmpty()) p.add(q.id() + ": нет шагов");
        Set<String> ids = new HashSet<>();
        for (Step s : q.steps()) {
            if (!ids.add(s.id())) p.add(q.id() + ": повторяется шаг " + s.id());
            if (!STEP_TYPES.contains(s.type())) p.add(q.id() + "/" + s.id() + ": неизвестный тип шага '" + s.type() + "'");
            if (s.type().equals("choice") && s.options().size() < 2) p.add(q.id() + "/" + s.id() + ": у выбора меньше двух вариантов");
        }
        for (Step s : q.steps()) {
            if (!s.next().isEmpty() && q.step(s.next()) == null) p.add(q.id() + "/" + s.id() + ": next '" + s.next() + "' не найден");
            if (!s.end().isEmpty() && !q.outcomes().containsKey(s.end())) p.add(q.id() + "/" + s.id() + ": исход '" + s.end() + "' не описан");
            for (Option o : s.options()) {
                if (!o.next().isEmpty() && q.step(o.next()) == null && !q.outcomes().containsKey(o.next())) p.add(q.id() + "/" + s.id() + ": вариант ведёт в '" + o.next() + "' (нет ни шага, ни исхода)");
                for (String k : o.effects().keySet()) if (!EFFECTS.contains(k)) p.add(q.id() + ": неизвестный эффект '" + k + "'");
            }
        }
        for (var e : q.outcomes().entrySet()) for (String k : e.getValue().keySet()) if (!EFFECTS.contains(k)) p.add(q.id() + ": исход " + e.getKey() + ": неизвестный эффект '" + k + "'");
        // цикл по next без выбора - задание не закончится
        for (Step s : q.steps()) {
            Set<String> seen = new HashSet<>();
            for (Step c = s; c != null && !c.next().isEmpty(); c = q.step(c.next())) if (!seen.add(c.id())) { p.add(q.id() + ": шаги зациклены"); break; }
        }
        return p;
    }
}
