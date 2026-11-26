package dev.worldforge.realms;

import dev.worldforge.mod.Json;

import java.util.*;

/**
 * Определения государственного строя из JSON (несколько файлов объединяются; одинаковый id перекрывает прежний).
 * Секции: governments, levels, terrains, doctrines (ветки развития), laws. Всё добавляется датапаком без перекомпиляции.
 */
public final class RealmDefs {
    public record Gov(String id, String name, String desc, String category, int color, String succession, int minLevel,
                      List<String> reqRaces, List<String> forbidRaces, Map<String, Double> reqTerrain, List<String> reqFlags,
                      Map<String, Double> affRaces, Map<String, Double> affTerrain, Map<String, Double> mods, int doctrineSlots, int lawSlots) {}
    public record Level(String id, String name, int minProvinces, int minPrestige, double points, int doctrineSlots, int lawSlots, Map<String, Double> mods) {}
    public record Terrain(String id, String name, List<String> biomes, Map<String, Double> mods) {}
    public record Node(String branch, String id, String name, String desc, int cost, int minLevel, List<String> requires, List<String> reqGov, Map<String, Double> mods) {
        public String key() { return branch + ":" + id; }
    }
    public record Branch(String id, String name, String desc, int color, List<Node> nodes) {}
    public record LawOption(String id, String name, int minLevel, Map<String, Double> mods) {}
    public record Law(String id, String name, String desc, int cooldownDays, List<LawOption> options) {}

    public final Map<String, Gov> govs = new LinkedHashMap<>();
    public final List<Level> levels = new ArrayList<>();
    public final Map<String, Terrain> terrains = new LinkedHashMap<>();
    public final Map<String, Branch> branches = new LinkedHashMap<>();
    public final Map<String, Law> laws = new LinkedHashMap<>();

    private static double dbl(Object o, double d) { return o instanceof Number n ? n.doubleValue() : d; }
    private static int num(Object o, int d) { return o instanceof Number n ? n.intValue() : d; }
    private static String str(Object o, String d) { return o == null ? d : String.valueOf(o); }
    private static Map<String, Double> dmap(Object o) {
        Map<String, Double> m = new LinkedHashMap<>();
        if (o instanceof Map<?, ?> mm) for (var e : mm.entrySet()) m.put(String.valueOf(e.getKey()), dbl(e.getValue(), 0));
        return m;
    }
    private static List<String> strs(Object o) {
        List<String> l = new ArrayList<>();
        if (o instanceof List<?> ll) for (Object x : ll) l.add(String.valueOf(x));
        return l;
    }
    @SuppressWarnings("unchecked") private static Map<String, Object> obj(Object o) { return (Map<String, Object>) o; }

    @SuppressWarnings("unchecked")
    public void load(String json) {
        if (!(Json.parse(json) instanceof Map<?, ?> root)) throw new IllegalArgumentException("Нужен объект JSON");
        Map<String, Object> m = (Map<String, Object>) root;
        if (m.get("governments") instanceof List<?> l) for (Object o : l) addGov(obj(o));
        if (m.get("levels") instanceof List<?> l) for (Object o : l) addLevel(obj(o));
        if (m.get("terrains") instanceof List<?> l) for (Object o : l) addTerrain(obj(o));
        if (m.get("doctrines") instanceof List<?> l) for (Object o : l) addBranch(obj(o));
        if (m.get("laws") instanceof List<?> l) for (Object o : l) addLaw(obj(o));
    }

    private static String id(Map<String, Object> o, String what) {
        String id = str(o.get("id"), "");
        if (id.isEmpty()) throw new IllegalArgumentException("У записи '" + what + "' нет id");
        return id;
    }

    public void addGov(Map<String, Object> g) {
        String id = id(g, "governments");
        govs.put(id, new Gov(id, str(g.get("name"), id), str(g.get("desc"), ""), str(g.get("category"), "other"), num(g.get("color"), 0x888888),
                str(g.get("succession"), "hereditary"), num(g.get("min_level"), 0), strs(g.get("req_races")), strs(g.get("forbid_races")),
                dmap(g.get("req_terrain")), strs(g.get("req_flags")), dmap(g.get("aff_races")), dmap(g.get("aff_terrain")), dmap(g.get("mods")),
                num(g.get("doctrine_slots"), 0), num(g.get("law_slots"), 0)));
    }

    public void addLevel(Map<String, Object> l) {
        String id = id(l, "levels");
        Level nl = new Level(id, str(l.get("name"), id), num(l.get("min_provinces"), 0), num(l.get("min_prestige"), 0), dbl(l.get("points"), 1),
                num(l.get("doctrine_slots"), 0), num(l.get("law_slots"), 0), dmap(l.get("mods")));
        levels.removeIf(x -> x.id().equals(id));
        levels.add(nl);
        levels.sort(Comparator.comparingInt(Level::minProvinces).thenComparingInt(Level::minPrestige));
    }

    public void addTerrain(Map<String, Object> t) {
        String id = id(t, "terrains");
        terrains.put(id, new Terrain(id, str(t.get("name"), id), strs(t.get("biomes")), dmap(t.get("mods"))));
    }

    public void addBranch(Map<String, Object> b) {
        String id = id(b, "doctrines");
        List<Node> nodes = new ArrayList<>();
        if (b.get("nodes") instanceof List<?> l) for (Object o : l) {
            Map<String, Object> n = obj(o);
            nodes.add(new Node(id, id(n, "node"), str(n.get("name"), str(n.get("id"), "")), str(n.get("desc"), ""), num(n.get("cost"), 5), num(n.get("min_level"), 0),
                    strs(n.get("requires")), strs(n.get("req_gov")), dmap(n.get("mods"))));
        }
        branches.put(id, new Branch(id, str(b.get("name"), id), str(b.get("desc"), ""), num(b.get("color"), 0x888888), nodes));
    }

    public void addLaw(Map<String, Object> l) {
        String id = id(l, "laws");
        List<LawOption> opts = new ArrayList<>();
        if (l.get("options") instanceof List<?> ol) for (Object o : ol) {
            Map<String, Object> x = obj(o);
            opts.add(new LawOption(id(x, "law option"), str(x.get("name"), ""), num(x.get("min_level"), 0), dmap(x.get("mods"))));
        }
        laws.put(id, new Law(id, str(l.get("name"), id), str(l.get("desc"), ""), num(l.get("cooldown_days"), 30), opts));
    }

    public Node node(String key) {
        int i = key.indexOf(':');
        if (i < 0) return null;
        Branch b = branches.get(key.substring(0, i));
        if (b == null) return null;
        for (Node n : b.nodes()) if (n.id().equals(key.substring(i + 1))) return n;
        return null;
    }

    /** Ошибки в данных (пустой список - всё хорошо). */
    public List<String> validate() {
        List<String> e = new ArrayList<>();
        if (levels.isEmpty()) e.add("нет уровней государства");
        if (govs.isEmpty()) e.add("нет форм правления");
        for (Gov g : govs.values()) {
            if (g.minLevel() >= levels.size()) e.add("строй " + g.id() + ": min_level вне диапазона");
            for (String t : g.reqTerrain().keySet()) if (!terrains.containsKey(t)) e.add("строй " + g.id() + ": неизвестная местность " + t);
            for (String t : g.affTerrain().keySet()) if (!terrains.containsKey(t)) e.add("строй " + g.id() + ": неизвестная местность " + t);
        }
        for (Branch b : branches.values()) {
            Set<String> ids = new HashSet<>();
            for (Node n : b.nodes()) {
                if (!ids.add(n.id())) e.add("ветка " + b.id() + ": дубль узла " + n.id());
                for (String r : n.requires()) if (node(r.contains(":") ? r : b.id() + ":" + r) == null) e.add("узел " + n.key() + ": нет требования " + r);
                for (String r : n.reqGov()) if (!govs.containsKey(r) && govs.values().stream().noneMatch(g -> g.category().equals(r))) e.add("узел " + n.key() + ": нет строя/категории " + r);
            }
        }
        for (Law l : laws.values()) if (l.options().size() < 2) e.add("закон " + l.id() + ": нужно минимум 2 варианта");
        return e;
    }
}
