package dev.worldforge.settle;

import dev.worldforge.mod.Json;

import java.util.*;

/**
 * Виды поселений из JSON (секция "settlement_types"): стоянка, хутор, деревня, город, крепость, порт и любые свои.
 * Вид задаёт условия роста (жители, здания, местность), допустимую стройку, лимиты, схему планировки и бонусы.
 * Не зависит от Settlement: игровая обвязка передаёт сюда числа и список построенных зданий.
 */
public final class SettleTypes {
    public record Type(String id, String name, String desc, int tier, int minPop, int maxPop, Map<String, Integer> reqBuildings,
                       Map<String, Double> reqTerrain, List<String> reqRaces, List<String> allowCategories, List<String> allowBuildings,
                       List<String> banBuildings, Map<String, Integer> limits, String scheme, int spacing,
                       Map<String, Double> affRaces, Map<String, Double> affTerrain, Map<String, Double> mods, String from) {}

    public final Map<String, Type> types = new LinkedHashMap<>();
    public final Set<String> schemes = Set.of("scatter", "ring", "grid", "linear", "walled", "terraces");

    private static double dbl(Object o, double d) { return o instanceof Number n ? n.doubleValue() : d; }
    private static int num(Object o, int d) { return o instanceof Number n ? n.intValue() : d; }
    private static List<String> strs(Object o) { List<String> l = new ArrayList<>(); if (o instanceof List<?> x) for (Object e : x) l.add(String.valueOf(e)); return l; }
    private static Map<String, Double> dm(Object o) { Map<String, Double> m = new LinkedHashMap<>(); if (o instanceof Map<?, ?> x) for (var e : x.entrySet()) m.put(String.valueOf(e.getKey()), dbl(e.getValue(), 0)); return m; }
    private static Map<String, Integer> im(Object o) { Map<String, Integer> m = new LinkedHashMap<>(); if (o instanceof Map<?, ?> x) for (var e : x.entrySet()) m.put(String.valueOf(e.getKey()), num(e.getValue(), 0)); return m; }

    @SuppressWarnings("unchecked")
    public void load(String json) {
        Object root = Json.parse(json);
        if (!(root instanceof Map)) throw new IllegalArgumentException("Нужен объект JSON");
        if (((Map<String, Object>) root).get("settlement_types") instanceof List<?> l) for (Object o : l) {
            Map<String, Object> m = (Map<String, Object>) o;
            if (!(m.get("id") instanceof String id) || !(m.get("name") instanceof String name)) throw new IllegalArgumentException("Вид поселения: нужны id и name");
            types.put(id, new Type(id, name, String.valueOf(m.getOrDefault("desc", "")), num(m.get("tier"), 0), num(m.get("min_pop"), 0), num(m.get("max_pop"), 9999),
                    im(m.get("req_buildings")), dm(m.get("req_terrain")), strs(m.get("req_races")), strs(m.get("categories")), strs(m.get("allow")),
                    strs(m.get("ban")), im(m.get("limits")), String.valueOf(m.getOrDefault("scheme", "scatter")), num(m.get("spacing"), 2),
                    dm(m.get("aff_races")), dm(m.get("aff_terrain")), dm(m.get("mods")), String.valueOf(m.getOrDefault("from", ""))));
        }
    }

    public List<String> validate(Defs defs) {
        List<String> p = new ArrayList<>();
        for (Type t : types.values()) {
            if (!schemes.contains(t.scheme())) p.add(t.id() + ": неизвестная схема '" + t.scheme() + "'");
            if (!t.from().isEmpty() && !types.containsKey(t.from())) p.add(t.id() + ": from неизвестный вид '" + t.from() + "'");
            if (t.minPop() > t.maxPop()) p.add(t.id() + ": min_pop больше max_pop");
            for (String b : t.reqBuildings().keySet()) if (!defs.buildings.containsKey(b)) p.add(t.id() + ": req_buildings неизвестное здание '" + b + "'");
            for (String b : t.allowBuildings()) if (!defs.buildings.containsKey(b)) p.add(t.id() + ": allow неизвестное здание '" + b + "'");
            for (String b : t.banBuildings()) if (!defs.buildings.containsKey(b)) p.add(t.id() + ": ban неизвестное здание '" + b + "'");
            for (String b : t.limits().keySet()) if (!defs.buildings.containsKey(b)) p.add(t.id() + ": limits неизвестное здание '" + b + "'");
            for (String c : t.allowCategories()) if (!Defs.CATEGORIES.contains(c)) p.add(t.id() + ": неизвестная категория '" + c + "'");
            Set<String> seen = new HashSet<>();
            for (String c = t.from(); !c.isEmpty() && types.containsKey(c); c = types.get(c).from()) if (!seen.add(c) || c.equals(t.id())) { p.add(t.id() + ": from образует цикл"); break; }
        }
        return p;
    }

    /** Почему вид недоступен (пустая строка - подходит). built - id построенных зданий (с повторами). */
    public String why(Type t, int pop, List<String> built, Map<String, Double> terrain, Map<String, Double> races) {
        if (pop < t.minPop()) return "нужно жителей: " + t.minPop();
        for (var e : t.reqBuildings().entrySet()) if (Collections.frequency(built, e.getKey()) < e.getValue()) return "нужно здание: " + e.getKey() + " x" + e.getValue();
        for (var e : t.reqTerrain().entrySet()) if (terrain.getOrDefault(e.getKey(), 0.0) < e.getValue()) return "нужно " + Math.round(e.getValue() * 100) + "% местности " + e.getKey();
        if (!t.reqRaces().isEmpty()) { double s = 0; for (String r : t.reqRaces()) s += races.getOrDefault(r, 0.0); if (s < 0.4) return "нужно 40% жителей: " + String.join("/", t.reqRaces()); }
        return "";
    }

    /** Лучший вид для поселения: сначала по ступени (tier), при равенстве по подгонке. Нет подходящего - null. */
    public Type best(int pop, List<String> built, Map<String, Double> terrain, Map<String, Double> races) {
        Type best = null; double bs = -1e9;
        for (Type t : types.values()) {
            if (!why(t, pop, built, terrain, races).isEmpty()) continue;
            double sc = t.tier() * 1000 + fit(t, terrain, races);
            if (sc > bs) { bs = sc; best = t; }
        }
        return best;
    }

    /** Подгонка вида к местности и жителям: от -50 до +50 процентов к его бонусам. */
    public double fit(Type t, Map<String, Double> terrain, Map<String, Double> races) {
        double s = 0;
        for (var e : t.affTerrain().entrySet()) s += e.getValue() * terrain.getOrDefault(e.getKey(), 0.0);
        for (var e : t.affRaces().entrySet()) s += e.getValue() * races.getOrDefault(e.getKey(), 0.0);
        return Math.max(-50, Math.min(50, s));
    }

    /** Можно ли строить здание в поселении этого вида; пустая строка - можно. have - сколько уже есть таких зданий. */
    public String canBuild(Type t, Defs.Building b, int have) {
        if (t.banBuildings().contains(b.id)) return "в таком поселении это не строят";
        boolean listed = t.allowBuildings().contains(b.id);
        if (!listed && !t.allowCategories().isEmpty() && !t.allowCategories().contains(b.category)) return "не подходит виду поселения «" + t.name() + "»";
        Integer lim = t.limits().get(b.id);
        if (lim != null && have >= lim) return "лимит для «" + t.name() + "»: " + lim;
        return "";
    }

    /** Бонусы вида с учётом подгонки (поправки в общую шину: tax_pct, safety, trade_pct и т.п.). */
    public Map<String, Double> mods(Type t, Map<String, Double> terrain, Map<String, Double> races) {
        double k = 1 + fit(t, terrain, races) / 100.0;
        Map<String, Double> out = new LinkedHashMap<>();
        t.mods().forEach((key, v) -> out.put(key, v * k));
        return out;
    }

    /** Схема планировки: n смещений (dx, dz) для участков зданий. Детерминированно по сиду. */
    public List<int[]> layout(Type t, int n, long seed) {
        Random rng = new Random(seed);
        int sp = Math.max(1, t.spacing()) + 4;                  // шаг: размер участка + промежуток
        List<int[]> out = new ArrayList<>();
        switch (t.scheme()) {
            case "ring" -> {                                    // кольцами вокруг центра
                out.add(new int[]{0, 0});
                for (int ring = 1; out.size() < n; ring++) {
                    int cnt = ring * 6;
                    for (int i = 0; i < cnt && out.size() < n; i++) {
                        double a = 2 * Math.PI * i / cnt;
                        out.add(new int[]{(int) Math.round(Math.cos(a) * ring * sp), (int) Math.round(Math.sin(a) * ring * sp)});
                    }
                }
            }
            case "grid" -> {                                    // кварталы по сетке
                int w = (int) Math.ceil(Math.sqrt(n));
                for (int i = 0; i < n; i++) out.add(new int[]{(i % w - w / 2) * sp, (i / w - w / 2) * sp});
            }
            case "linear" -> {                                  // вдоль дороги (улица или берег)
                for (int i = 0; i < n; i++) out.add(new int[]{(i / 2 - n / 4) * sp, (i % 2 == 0 ? -1 : 1) * (sp / 2 + 1)});
            }
            case "walled" -> {                                  // двор внутри стен: внутренние кольца без внешних
                int w = (int) Math.ceil(Math.sqrt(n));
                for (int i = 0; i < n; i++) out.add(new int[]{(i % w - w / 2) * (sp - 1), (i / w - w / 2) * (sp - 1)});
            }
            case "terraces" -> {                                // уступами по склону (горные поселения)
                for (int i = 0; i < n; i++) out.add(new int[]{(i % 4 - 2) * sp, (i / 4) * sp});
            }
            default -> {                                        // scatter: хаотично, но без наложений
                int tries = 0;
                while (out.size() < n && tries++ < n * 400) {
                    int r = sp * (1 + (int) Math.sqrt(out.size() + 1)), x = rng.nextInt(2 * r + 1) - r, z = rng.nextInt(2 * r + 1) - r;
                    boolean ok = true;
                    for (int[] o : out) if (Math.abs(o[0] - x) < sp && Math.abs(o[1] - z) < sp) { ok = false; break; }
                    if (ok) out.add(new int[]{x, z});
                }
                while (out.size() < n) out.add(new int[]{out.size() * sp, 0});
            }
        }
        return out;
    }
}
