package dev.worldforge.genesis;

import dev.worldforge.mod.Json;

import java.util.*;

/**
 * Данные мастера создания персонажа из JSON (data/&lt;ns&gt;/wf_genesis/*.json; наборы объединяются):
 * "config" (очки и пределы), "sliders", "palettes", "hair_styles", "features", "race_look" (внешность по умолчанию),
 * "styles" (названия поселений и титулы по расам), "tiers" (размер стартового поселения), "focus", "starts", "name_pool".
 */
public final class GenesisDefs {
    public record Slider(String id, String name, String desc, int min, int max, int def, String unit) {}
    public record Feature(String id, String name, String desc, String group, List<String> races, Map<String, Double> mods) {}
    public record Start(String id, String name, String desc, String kind, String tier, List<String> races, List<String> notRaces, String spawn, boolean panel,
                        int gold, Map<String, Integer> items, int attrBonus, int skillBonus, int profXp, List<String> flags, Map<String, Double> mods,
                        Map<String, Integer> standing, boolean wanted) {
        public boolean leader() { return kind.equals("leader"); }
    }
    public record Focus(String id, String name, String desc, Map<String, Integer> stock, int workers) {}
    public record Tier(String id, String name, int workers, Map<String, Integer> stock, List<String> buildings) {}

    public int attrBudget = 40, attrMaxDelta = 8, attrSoftCap = 4, profPicks = 2, skillPoints = 3, nameMax = 24;
    public final Map<String, String> groupNames = new LinkedHashMap<>();
    public final List<Slider> sliders = new ArrayList<>();
    public final Map<String, List<Integer>> palettes = new LinkedHashMap<>();
    public final List<String[]> hairStyles = new ArrayList<>();
    public final Map<String, Feature> features = new LinkedHashMap<>();
    public final Map<String, Map<String, Object>> raceLook = new LinkedHashMap<>();
    public final Map<String, Map<String, String>> styles = new LinkedHashMap<>();
    public final Map<String, Tier> tiers = new LinkedHashMap<>();
    public final Map<String, Focus> focus = new LinkedHashMap<>();
    public final Map<String, Start> starts = new LinkedHashMap<>();
    public final Map<String, List<String>> namePool = new LinkedHashMap<>();
    /** Ключи цветов внешности: из палитры берутся стартовые варианты. */
    public static final List<String> COLOR_KEYS = List.of("skin", "hair", "eyes");

    /** Очищает всё (перед перезагрузкой датапаков). */
    public void reset() {
        attrBudget = 40; attrMaxDelta = 8; attrSoftCap = 4; profPicks = 2; skillPoints = 3; nameMax = 24;
        groupNames.clear(); sliders.clear(); palettes.clear(); hairStyles.clear(); features.clear(); raceLook.clear(); styles.clear(); tiers.clear(); focus.clear(); starts.clear(); namePool.clear();
    }

    private static int num(Object o, int d) { return o instanceof Number n ? n.intValue() : d; }
    private static String str(Object o, String d) { return o == null ? d : String.valueOf(o); }
    private static List<String> strs(Object o) { List<String> l = new ArrayList<>(); if (o instanceof List<?> ll) for (Object x : ll) l.add(String.valueOf(x)); return l; }
    private static Map<String, Integer> ints(Object o) { Map<String, Integer> m = new LinkedHashMap<>(); if (o instanceof Map<?, ?> mm) for (var e : mm.entrySet()) m.put(String.valueOf(e.getKey()), num(e.getValue(), 0)); return m; }
    private static Map<String, Double> dbls(Object o) { Map<String, Double> m = new LinkedHashMap<>(); if (o instanceof Map<?, ?> mm) for (var e : mm.entrySet()) m.put(String.valueOf(e.getKey()), e.getValue() instanceof Number n ? n.doubleValue() : 0.0); return m; }

    @SuppressWarnings("unchecked")
    public void load(String json) {
        Object r = Json.parse(json);
        if (!(r instanceof Map)) throw new IllegalArgumentException("Нужен объект JSON");
        Map<String, Object> m = (Map<String, Object>) r;
        if (m.get("config") instanceof Map<?, ?> c) {
            attrBudget = num(c.get("attr_budget"), attrBudget); attrMaxDelta = num(c.get("attr_max_delta"), attrMaxDelta); attrSoftCap = num(c.get("attr_soft_cap"), attrSoftCap);
            profPicks = num(c.get("prof_picks"), profPicks); skillPoints = num(c.get("skill_points"), skillPoints); nameMax = num(c.get("name_max"), nameMax);
        }
        if (m.get("groups") instanceof Map<?, ?> g) for (var e : g.entrySet()) groupNames.put(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
        if (m.get("sliders") instanceof List<?> l) for (Object o : l) {
            Map<String, Object> s = (Map<String, Object>) o;
            String id = str(s.get("id"), "");
            sliders.removeIf(x -> x.id().equals(id));
            sliders.add(new Slider(id, str(s.get("name"), id), str(s.get("desc"), ""), num(s.get("min"), 0), num(s.get("max"), 100), num(s.get("def"), 50), str(s.get("unit"), "")));
        }
        if (m.get("palettes") instanceof Map<?, ?> p) for (var e : p.entrySet()) {
            List<Integer> cols = new ArrayList<>();
            if (e.getValue() instanceof List<?> l) for (Object o : l) cols.add(o instanceof Number n ? n.intValue() : Colors.rgb(String.valueOf(o)));
            palettes.put(String.valueOf(e.getKey()), cols);
        }
        if (m.get("hair_styles") instanceof List<?> l) for (Object o : l) { List<String> a = strs(o); if (a.size() >= 2) hairStyles.add(new String[]{a.get(0), a.get(1)}); }
        if (m.get("features") instanceof List<?> l) for (Object o : l) {
            Map<String, Object> f = (Map<String, Object>) o;
            String id = str(f.get("id"), "");
            if (id.isEmpty()) throw new IllegalArgumentException("У особенности внешности нет id");
            features.put(id, new Feature(id, str(f.get("name"), id), str(f.get("desc"), ""), str(f.get("group"), id), strs(f.get("races")), dbls(f.get("mods"))));
        }
        if (m.get("race_look") instanceof Map<?, ?> rl) for (var e : rl.entrySet()) raceLook.computeIfAbsent(String.valueOf(e.getKey()), k -> new LinkedHashMap<>()).putAll((Map<String, Object>) e.getValue());
        if (m.get("styles") instanceof Map<?, ?> st) for (var e : st.entrySet()) {
            Map<String, String> s = styles.computeIfAbsent(String.valueOf(e.getKey()), k -> new LinkedHashMap<>());
            for (var x : ((Map<?, ?>) e.getValue()).entrySet()) s.put(String.valueOf(x.getKey()), String.valueOf(x.getValue()));
        }
        if (m.get("tiers") instanceof Map<?, ?> t) for (var e : t.entrySet()) {
            Map<String, Object> x = (Map<String, Object>) e.getValue();
            tiers.put(String.valueOf(e.getKey()), new Tier(String.valueOf(e.getKey()), str(x.get("name"), String.valueOf(e.getKey())), num(x.get("workers"), 4), ints(x.get("stock")), strs(x.get("buildings"))));
        }
        if (m.get("focus") instanceof Map<?, ?> t) for (var e : t.entrySet()) {
            Map<String, Object> x = (Map<String, Object>) e.getValue();
            focus.put(String.valueOf(e.getKey()), new Focus(String.valueOf(e.getKey()), str(x.get("name"), String.valueOf(e.getKey())), str(x.get("desc"), ""), ints(x.get("stock")), num(x.get("workers"), 0)));
        }
        if (m.get("name_pool") instanceof Map<?, ?> np) for (var e : np.entrySet()) namePool.computeIfAbsent(String.valueOf(e.getKey()), k -> new ArrayList<>()).addAll(strs(e.getValue()));
        if (m.get("starts") instanceof List<?> l) for (Object o : l) {
            Map<String, Object> s = (Map<String, Object>) o;
            String id = str(s.get("id"), "");
            if (id.isEmpty()) throw new IllegalArgumentException("У старта нет id");
            starts.put(id, new Start(id, str(s.get("name"), id), str(s.get("desc"), ""), str(s.get("kind"), "member"), str(s.get("tier"), ""), strs(s.get("races")), strs(s.get("not_races")),
                    str(s.get("spawn"), "random"), Boolean.TRUE.equals(s.get("panel")), num(s.get("gold"), 0), ints(s.get("items")), num(s.get("attr_bonus"), 0), num(s.get("skill_bonus"), 0), num(s.get("prof_xp"), 0),
                    strs(s.get("flags")), dbls(s.get("mods")), ints(s.get("standing")), Boolean.TRUE.equals(s.get("wanted"))));
        }
    }

    public Slider slider(String id) { for (Slider s : sliders) if (s.id().equals(id)) return s; return null; }

    /** Название из стиля расы (ключ вроде "village", "ruler_town"): ищется по цепочке расы от подрасы к корню, потом в "_default". */
    public String style(List<String> raceChainLeafFirst, String key, String fallback) {
        for (String r : raceChainLeafFirst) { Map<String, String> s = styles.get(r); if (s != null && s.containsKey(key)) return s.get(key); }
        Map<String, String> d = styles.get("_default");
        return d != null && d.containsKey(key) ? d.get(key) : fallback;
    }

    public List<String> validate(Collection<String> raceIds, Collection<String> attrIds, Collection<String> professionIds, Collection<String> settleBuildings, Collection<String> itemsOk) {
        List<String> p = new ArrayList<>();
        for (Start s : starts.values()) {
            for (String r : s.races()) if (!raceIds.contains(r)) p.add("Старт " + s.id() + ": нет расы " + r);
            for (String r : s.notRaces()) if (!raceIds.contains(r)) p.add("Старт " + s.id() + ": нет расы " + r);
            if (s.leader() && !tiers.containsKey(s.tier())) p.add("Старт " + s.id() + ": нет уровня поселения '" + s.tier() + "'");
            if (!List.of("member", "leader", "outlaw", "wanderer").contains(s.kind())) p.add("Старт " + s.id() + ": неизвестный вид " + s.kind());
            if (!List.of("settlement", "random", "here", "camp").contains(s.spawn())) p.add("Старт " + s.id() + ": неизвестное место " + s.spawn());
        }
        for (Feature f : features.values()) for (String r : f.races()) if (!raceIds.contains(r)) p.add("Особенность " + f.id() + ": нет расы " + r);
        for (String r : raceLook.keySet()) if (!raceIds.contains(r)) p.add("race_look: нет расы " + r);
        for (String r : styles.keySet()) if (!r.equals("_default") && !raceIds.contains(r)) p.add("styles: нет расы " + r);
        for (Tier t : tiers.values()) for (String b : t.buildings()) if (!settleBuildings.isEmpty() && !settleBuildings.contains(b)) p.add("Уровень " + t.id() + ": нет здания " + b);
        if (starts.isEmpty()) p.add("Нет ни одного старта");
        if (sliders.isEmpty()) p.add("Предупреждение: нет ползунков внешности");
        return p;
    }
}
