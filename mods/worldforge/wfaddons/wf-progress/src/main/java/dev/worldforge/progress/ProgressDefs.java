package dev.worldforge.progress;

import dev.worldforge.mod.Json;

import java.util.*;

/**
 * Определения прокачки из JSON (можно разбить на несколько файлов, секции объединяются): кривая уровней, атрибуты, навыки,
 * дерево пассивных умений и архетипы (стартовые точки и веса для автосборки NPC). Все числа бонусов - в словарях "статистика: значение";
 * ключи с суффиксом _pct - проценты.
 */
public final class ProgressDefs {
    public record Attr(String id, String name, String group, String desc, int color, int base, Map<String, Double> perPoint) {}
    public record Skill(String id, String name, String group, String desc, int max, List<Integer> cost, int minLevel, Map<String, Integer> reqAttr, Map<String, Integer> reqSkill, Map<String, Double> perRank) {}
    public record Node(String id, String name, String kind, String desc, double x, double y, int minLevel, Map<String, Double> mods, List<String> links) {}
    public record Archetype(String id, String name, String desc, String start, Map<String, Double> weights, Map<String, Double> attrWeights, Map<String, Double> skillWeights) {}
    public record Stat(String id, String name, String fmt, double base) {}

    public int maxLevel = 60, attrPoints = 2, skillPoints = 1, passivePoints = 1;
    public double xpBase = 100, xpGrowth = 1.16;
    public final Map<String, Double> perLevel = new LinkedHashMap<>();
    public final Map<Integer, Map<String, Integer>> milestones = new LinkedHashMap<>();
    public final Map<String, Attr> attrs = new LinkedHashMap<>();
    public final Map<String, Skill> skills = new LinkedHashMap<>();
    public final Map<String, Node> nodes = new LinkedHashMap<>();
    public final Map<String, Archetype> archetypes = new LinkedHashMap<>();
    public final Map<String, Stat> stats = new LinkedHashMap<>();

    private static double dbl(Object o, double d) { return o instanceof Number n ? n.doubleValue() : d; }
    private static int num(Object o, int d) { return o instanceof Number n ? n.intValue() : d; }
    private static String str(Object o, String d) { return o == null ? d : String.valueOf(o); }

    @SuppressWarnings("unchecked")
    private static Map<String, Double> mods(Object o) {
        Map<String, Double> m = new LinkedHashMap<>();
        if (o instanceof Map<?, ?> mm) for (var e : mm.entrySet()) m.put(String.valueOf(e.getKey()), dbl(e.getValue(), 0));
        return m;
    }

    private static Map<String, Integer> ints(Object o) {
        Map<String, Integer> m = new LinkedHashMap<>();
        if (o instanceof Map<?, ?> mm) for (var e : mm.entrySet()) m.put(String.valueOf(e.getKey()), num(e.getValue(), 0));
        return m;
    }

    /** Загружает один файл; секции можно повторять в разных файлах, одинаковые id заменяются. */
    @SuppressWarnings("unchecked")
    public void load(String json) {
        Object root = Json.parse(json);
        if (!(root instanceof Map)) throw new IllegalArgumentException("Нужен объект JSON");
        Map<String, Object> m = (Map<String, Object>) root;
        if (m.get("levels") instanceof Map<?, ?> l) {
            maxLevel = num(l.get("max"), maxLevel); xpBase = dbl(l.get("xp_base"), xpBase); xpGrowth = dbl(l.get("xp_growth"), xpGrowth);
            attrPoints = num(l.get("attr_points"), attrPoints); skillPoints = num(l.get("skill_points"), skillPoints); passivePoints = num(l.get("passive_points"), passivePoints);
            if (l.get("per_level") != null) { perLevel.clear(); perLevel.putAll(mods(l.get("per_level"))); }
            if (l.get("milestones") instanceof Map<?, ?> ms) for (var e : ms.entrySet()) milestones.put(Integer.parseInt(String.valueOf(e.getKey())), ints(e.getValue()));
        }
        if (m.get("stats") instanceof List<?> l) for (Object o : l) {
            Map<String, Object> s = (Map<String, Object>) o;
            stats.put(str(s.get("id"), ""), new Stat(str(s.get("id"), ""), str(s.get("name"), str(s.get("id"), "")), str(s.get("fmt"), "int"), dbl(s.get("base"), 0)));
        }
        if (m.get("attributes") instanceof List<?> l) for (Object o : l) {
            Map<String, Object> a = (Map<String, Object>) o;
            String id = str(a.get("id"), "");
            attrs.put(id, new Attr(id, str(a.get("name"), id), str(a.get("group"), "body"), str(a.get("desc"), ""), num(a.get("color"), 0xAAAAAA), num(a.get("base"), 5), mods(a.get("per_point"))));
        }
        if (m.get("skills") instanceof List<?> l) for (Object o : l) {
            Map<String, Object> s = (Map<String, Object>) o;
            String id = str(s.get("id"), "");
            int max = num(s.get("max"), 5);
            List<Integer> cost = new ArrayList<>();
            if (s.get("cost") instanceof List<?> cl) for (Object c : cl) cost.add(num(c, 1));
            while (cost.size() < max) cost.add(cost.isEmpty() ? 1 : cost.get(cost.size() - 1));
            Map<String, Object> rq = s.get("requires") instanceof Map<?, ?> rm ? (Map<String, Object>) rm : Map.of();
            skills.put(id, new Skill(id, str(s.get("name"), id), str(s.get("group"), "general"), str(s.get("desc"), ""), max, cost, num(rq.get("level"), 1), ints(rq.get("attr")), ints(rq.get("skill")), mods(s.get("per_rank"))));
        }
        if (m.get("passives") instanceof Map<?, ?> p) {
            if (p.get("nodes") instanceof List<?> l) for (Object o : l) {
                Map<String, Object> n = (Map<String, Object>) o;
                String id = str(n.get("id"), "");
                List<String> links = new ArrayList<>();
                if (n.get("links") instanceof List<?> ll) for (Object x : ll) links.add(String.valueOf(x));
                nodes.put(id, new Node(id, str(n.get("name"), id), str(n.get("kind"), "small"), str(n.get("desc"), ""), dbl(n.get("x"), 0), dbl(n.get("y"), 0), num(n.get("min_level"), 1), mods(n.get("mods")), links));
            }
            if (p.get("archetypes") instanceof List<?> l) for (Object o : l) {
                Map<String, Object> a = (Map<String, Object>) o;
                String id = str(a.get("id"), "");
                archetypes.put(id, new Archetype(id, str(a.get("name"), id), str(a.get("desc"), ""), str(a.get("start"), ""), mods(a.get("weights")), mods(a.get("attr_weights")), mods(a.get("skill_weights"))));
            }
        }
    }

    /** Ошибки: ссылки на несуществующее, несимметричные связи, недостижимые узлы. Пустой список = всё хорошо; "Предупреждение" не мешает. */
    public List<String> validate() {
        List<String> p = new ArrayList<>();
        for (Node n : nodes.values()) {
            for (String l : n.links()) {
                Node o = nodes.get(l);
                if (o == null) p.add("Узел " + n.id() + ": связь с несуществующим " + l);
                else if (!o.links().contains(n.id())) p.add("Узел " + n.id() + ": связь с " + l + " не симметрична");
            }
            if (!Set.of("start", "small", "notable", "keystone").contains(n.kind())) p.add("Узел " + n.id() + ": вид " + n.kind());
        }
        for (Archetype a : archetypes.values()) {
            Node s = nodes.get(a.start());
            if (s == null || !s.kind().equals("start")) p.add("Архетип " + a.id() + ": нет стартового узла " + a.start());
        }
        for (Skill s : skills.values()) {
            for (String r : s.reqSkill().keySet()) if (!skills.containsKey(r)) p.add("Навык " + s.id() + ": требует несуществующий навык " + r);
            for (String r : s.reqAttr().keySet()) if (!attrs.containsKey(r)) p.add("Навык " + s.id() + ": требует несуществующий атрибут " + r);
        }
        Set<String> seen = new HashSet<>();
        Deque<String> q = new ArrayDeque<>();
        for (Archetype a : archetypes.values()) if (nodes.containsKey(a.start())) { seen.add(a.start()); q.add(a.start()); }
        while (!q.isEmpty()) for (String l : nodes.get(q.poll()).links()) if (nodes.containsKey(l) && seen.add(l)) q.add(l);
        for (String id : nodes.keySet()) if (!seen.contains(id)) p.add("Предупреждение: узел " + id + " недостижим от стартов");
        if (!stats.isEmpty()) {
            Set<String> known = new HashSet<>(stats.keySet());
            for (String k : stats.keySet()) known.add(k + "_pct");
            Set<String> bad = new TreeSet<>();
            List<Map<String, Double>> all = new ArrayList<>();
            for (Node n : nodes.values()) all.add(n.mods());
            for (Skill s : skills.values()) all.add(s.perRank());
            for (Attr a : attrs.values()) all.add(a.perPoint());
            for (Map<String, Double> mm : all) for (String k : mm.keySet()) if (!known.contains(k) && !attrs.containsKey(k)) bad.add(k);
            for (String k : bad) p.add("Предупреждение: неизвестная статистика " + k);
        }
        return p;
    }

    public long xpFor(int level) { return Math.round(xpBase * Math.pow(xpGrowth, level - 1)); }
}
