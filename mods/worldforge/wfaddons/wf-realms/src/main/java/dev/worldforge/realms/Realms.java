package dev.worldforge.realms;

import dev.worldforge.mod.Json;
import dev.worldforge.mod.api.StatApi;

import java.util.*;

/**
 * Строй, уровень, местность и ветки развития государств.
 * Владелец в {@link StatApi} это "r:&lt;id государства&gt;": бонусы строя, уровня, местности, доктрин и законов видны всем аддонам.
 * Подгонка строя ("fit") зависит от доли рас и местностей в государстве: гномам горы и гильдейская республика, оркам орда.
 */
public final class Realms {
    public interface Hooks {
        default int provinces(String realm) { return 1; }
        default int prestige(String realm) { return 0; }
        /** Доли местностей (id из terrains) в государстве, сумма примерно 1. */
        default Map<String, Double> terrain(String realm) { return dev.worldforge.mod.api.DemoApi.terrain(realm); }
        /** Доли рас среди жителей. */
        default Map<String, Double> races(String realm) { return dev.worldforge.mod.api.DemoApi.races(realm); }
        default boolean flag(String realm, String flag) { return false; }
        default int today() { return 0; }
    }

    public static final class State {
        public String gov = "";
        public double points;
        public int govChanged = -9999;
        public final Set<String> nodes = new LinkedHashSet<>();
        public final Map<String, String> laws = new LinkedHashMap<>();
        public final Map<String, Integer> lawChanged = new HashMap<>();
        public int lastTick = -1;
    }

    public final RealmDefs defs;
    public Hooks hooks = new Hooks() {};
    public String lastError = "";
    public static final int GOV_COOLDOWN = 30;
    private final Map<String, State> states = new LinkedHashMap<>();

    public Realms(RealmDefs defs) { this.defs = defs; }

    public State state(String realm) { return states.computeIfAbsent(realm, k -> new State()); }

    public void publish() {
        StatApi.register("realms", o -> o.startsWith("r:") ? mods(o.substring(2)) : Map.of());
        StatApi.registerTags("realms", o -> {
            if (!o.startsWith("r:")) return Map.of();
            String r = o.substring(2);
            State s = state(r);
            Map<String, String> t = new LinkedHashMap<>();
            if (!s.gov.isEmpty()) { t.put("gov", s.gov); RealmDefs.Gov g = defs.govs.get(s.gov); if (g != null) t.put("gov_category", g.category()); }
            t.put("realm_level", defs.levels.isEmpty() ? "" : defs.levels.get(level(r)).id());
            return t;
        });
    }

    private boolean fail(String m) { lastError = m; return false; }

    // ---------- уровень ----------

    public int level(String realm) {
        int p = hooks.provinces(realm), pr = hooks.prestige(realm), best = 0;
        for (int i = 0; i < defs.levels.size(); i++) if (p >= defs.levels.get(i).minProvinces() && pr >= defs.levels.get(i).minPrestige()) best = i;
        return best;
    }

    public RealmDefs.Level levelDef(String realm) { return defs.levels.get(level(realm)); }

    // ---------- строй ----------

    private double share(Map<String, Double> m, String k) { return m.getOrDefault(k, 0.0); }

    /** Подгонка строя к населению и местности: от -50 до +50 процентов к его бонусам. */
    public double fit(String realm, RealmDefs.Gov g) {
        Map<String, Double> races = hooks.races(realm), terr = hooks.terrain(realm);
        double s = 0;
        for (var e : g.affRaces().entrySet()) s += e.getValue() * share(races, e.getKey());
        for (var e : g.affTerrain().entrySet()) s += e.getValue() * share(terr, e.getKey());
        return Math.max(-50, Math.min(50, s));
    }

    /** Почему строй недоступен (пустая строка - доступен). */
    public String why(String realm, RealmDefs.Gov g) {
        if (level(realm) < g.minLevel()) return "нужен уровень «" + defs.levels.get(g.minLevel()).name() + "»";
        Map<String, Double> races = hooks.races(realm), terr = hooks.terrain(realm);
        if (!g.reqRaces().isEmpty()) {
            double sum = 0;
            for (String r : g.reqRaces()) sum += share(races, r);
            if (sum < 0.4) return "нужно не менее 40% жителей: " + String.join("/", g.reqRaces());
        }
        for (String r : g.forbidRaces()) if (share(races, r) >= 0.5) return "не подходит расе: " + r;
        for (var e : g.reqTerrain().entrySet()) if (share(terr, e.getKey()) < e.getValue()) return "нужно " + Math.round(e.getValue() * 100) + "% местности " + e.getKey();
        for (String f : g.reqFlags()) if (!hooks.flag(realm, f)) return "нужно условие: " + f;
        return "";
    }

    public boolean setGov(String realm, String id, boolean force) {
        RealmDefs.Gov g = defs.govs.get(id);
        if (g == null) return fail("нет такого строя");
        State s = state(realm);
        if (s.gov.equals(id)) return fail("уже этот строй");
        if (!force) {
            String w = why(realm, g);
            if (!w.isEmpty()) return fail(w);
            int left = GOV_COOLDOWN - (hooks.today() - s.govChanged);
            if (left > 0) return fail("смена строя через " + left + " дн.");
        }
        s.gov = id;
        s.govChanged = hooks.today();
        s.laws.clear();
        s.nodes.removeIf(k -> { RealmDefs.Node n = defs.node(k); return n != null && !nodeGovOk(n, id); });
        return true;
    }

    /** Лучший доступный строй для ИИ-государства: по подгонке, с детерминированной случайностью. */
    public String suggest(String realm, long seed) {
        Random rng = new Random(seed);
        String best = "";
        double bs = -1e9;
        for (RealmDefs.Gov g : defs.govs.values()) {
            if (!why(realm, g).isEmpty()) continue;
            double sc = fit(realm, g) + rng.nextDouble() * 15;
            if (sc > bs) { bs = sc; best = g.id(); }
        }
        return best;
    }

    private boolean nodeGovOk(RealmDefs.Node n, String gov) {
        if (n.reqGov().isEmpty()) return true;
        RealmDefs.Gov g = defs.govs.get(gov);
        return g != null && (n.reqGov().contains(gov) || n.reqGov().contains(g.category()));
    }

    // ---------- доктрины ----------

    public int doctrineSlots(String realm) {
        State s = state(realm);
        RealmDefs.Gov g = defs.govs.get(s.gov);
        return levelDef(realm).doctrineSlots() + (g == null ? 0 : g.doctrineSlots());
    }

    public int lawSlots(String realm) {
        State s = state(realm);
        RealmDefs.Gov g = defs.govs.get(s.gov);
        return levelDef(realm).lawSlots() + (g == null ? 0 : g.lawSlots());
    }

    /** Ежедневное начисление очков развития (от уровня государства). Возвращает число прошедших дней. */
    public int tick(String realm) {
        State s = state(realm);
        int today = hooks.today();
        if (s.lastTick < 0) { s.lastTick = today; return 0; }
        int d = Math.max(0, today - s.lastTick);
        s.lastTick = today;
        s.points += d * levelDef(realm).points() * (1 + mods(realm).getOrDefault("doctrine_pct", 0.0) / 100.0);
        return d;
    }

    public boolean canUnlock(String realm, String key) {
        State s = state(realm);
        RealmDefs.Node n = defs.node(key);
        if (n == null) return fail("нет такого узла");
        if (s.nodes.contains(key)) return fail("уже изучено");
        if (s.gov.isEmpty()) return fail("сначала выберите строй");
        if (level(realm) < n.minLevel()) return fail("нужен уровень «" + defs.levels.get(Math.min(n.minLevel(), defs.levels.size() - 1)).name() + "»");
        if (!nodeGovOk(n, s.gov)) return fail("не подходит строю");
        for (String r : n.requires()) if (!s.nodes.contains(r.contains(":") ? r : n.branch() + ":" + r)) return fail("нужно: " + r);
        if (s.nodes.size() >= doctrineSlots(realm)) return fail("нет свободных ячеек доктрин");
        if (s.points < n.cost()) return fail("нужно очков: " + n.cost());
        return true;
    }

    public boolean unlock(String realm, String key) {
        if (!canUnlock(realm, key)) return false;
        State s = state(realm);
        s.points -= defs.node(key).cost();
        s.nodes.add(key);
        return true;
    }

    // ---------- законы ----------

    public boolean setLaw(String realm, String law, String option) {
        State s = state(realm);
        RealmDefs.Law l = defs.laws.get(law);
        if (l == null) return fail("нет такого закона");
        RealmDefs.LawOption o = l.options().stream().filter(x -> x.id().equals(option)).findFirst().orElse(null);
        if (o == null) return fail("нет такого варианта");
        if (level(realm) < o.minLevel()) return fail("нужен более высокий уровень государства");
        if (option.equals(s.laws.get(law))) return fail("уже действует");
        if (!s.laws.containsKey(law) && s.laws.size() >= lawSlots(realm)) return fail("нет свободных ячеек законов");
        int left = l.cooldownDays() - (hooks.today() - s.lawChanged.getOrDefault(law, -9999));
        if (left > 0) return fail("менять закон можно через " + left + " дн.");
        s.laws.put(law, option);
        s.lawChanged.put(law, hooks.today());
        return true;
    }

    // ---------- поправки ----------

    private static void add(Map<String, Double> out, Map<String, Double> m, double k) { m.forEach((a, b) -> out.merge(a, b * k, Double::sum)); }

    public Map<String, Double> mods(String realm) {
        Map<String, Double> out = new LinkedHashMap<>();
        State s = state(realm);
        if (!defs.levels.isEmpty()) add(out, levelDef(realm).mods(), 1);
        RealmDefs.Gov g = defs.govs.get(s.gov);
        if (g != null) add(out, g.mods(), 1 + fit(realm, g) / 100.0);
        for (var e : hooks.terrain(realm).entrySet()) {
            RealmDefs.Terrain t = defs.terrains.get(e.getKey());
            if (t != null) add(out, t.mods(), e.getValue());
        }
        for (String k : s.nodes) { RealmDefs.Node n = defs.node(k); if (n != null) add(out, n.mods(), 1); }
        for (var e : s.laws.entrySet()) {
            RealmDefs.Law l = defs.laws.get(e.getKey());
            if (l != null) for (var o : l.options()) if (o.id().equals(e.getValue())) add(out, o.mods(), 1);
        }
        return out;
    }

    // ---------- ИИ ----------

    /** Простое развитие для государств без игрока: строй по подгонке, доктрины и законы по дешёвизне. */
    public void autoDevelop(String realm, long seed) {
        State s = state(realm);
        if (s.gov.isEmpty()) { String g = suggest(realm, seed); if (!g.isEmpty()) setGov(realm, g, true); }
        tick(realm);
        List<String> keys = new ArrayList<>();
        for (RealmDefs.Branch b : defs.branches.values()) for (RealmDefs.Node n : b.nodes()) keys.add(n.key());
        keys.sort(Comparator.comparingInt(k -> defs.node(k).cost()));
        for (String k : keys) if (canUnlock(realm, k)) unlock(realm, k);
        for (RealmDefs.Law l : defs.laws.values()) if (!s.laws.containsKey(l.id())) setLaw(realm, l.id(), l.options().get((int) Math.floorMod(seed + l.id().hashCode(), l.options().size())).id());
    }

    // ---------- вид и сохранение ----------

    public String viewJson(String realm) {
        State s = state(realm);
        Map<String, Object> root = new LinkedHashMap<>();
        RealmDefs.Level lv = levelDef(realm);
        root.put("level", lv.name());
        root.put("levelIndex", level(realm));
        root.put("gov", s.gov);
        root.put("points", Math.round(s.points * 10) / 10.0);
        root.put("slots", doctrineSlots(realm));
        root.put("lawSlots", lawSlots(realm));
        List<Object> gl = new ArrayList<>();
        for (RealmDefs.Gov g : defs.govs.values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", g.id()); m.put("name", g.name()); m.put("category", g.category()); m.put("color", g.color());
            m.put("why", why(realm, g)); m.put("fit", Math.round(fit(realm, g)));
            gl.add(m);
        }
        root.put("govs", gl);
        root.put("nodes", new ArrayList<>(s.nodes));
        root.put("laws", new LinkedHashMap<>(s.laws));
        root.put("mods", mods(realm));
        return Json.write(root);
    }

    public String toJson() {
        Map<String, Object> root = new LinkedHashMap<>();
        for (var e : states.entrySet()) {
            State s = e.getValue();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("gov", s.gov); m.put("points", s.points); m.put("govChanged", s.govChanged); m.put("lastTick", s.lastTick);
            m.put("nodes", new ArrayList<>(s.nodes)); m.put("laws", new LinkedHashMap<>(s.laws)); m.put("lawChanged", new LinkedHashMap<>(s.lawChanged));
            root.put(e.getKey(), m);
        }
        return Json.write(root);
    }

    public void fromJson(String json) {
        states.clear();
        if (!(Json.parse(json) instanceof Map<?, ?> root)) return;
        for (var e : root.entrySet()) {
            if (!(e.getValue() instanceof Map<?, ?> m)) continue;
            State s = state(String.valueOf(e.getKey()));
            s.gov = String.valueOf(m.get("gov") == null ? "" : m.get("gov"));
            s.points = m.get("points") instanceof Number n ? n.doubleValue() : 0;
            s.govChanged = m.get("govChanged") instanceof Number n ? n.intValue() : -9999;
            s.lastTick = m.get("lastTick") instanceof Number n ? n.intValue() : -1;
            if (m.get("nodes") instanceof List<?> l) for (Object o : l) s.nodes.add(String.valueOf(o));
            if (m.get("laws") instanceof Map<?, ?> lm) for (var x : lm.entrySet()) s.laws.put(String.valueOf(x.getKey()), String.valueOf(x.getValue()));
            if (m.get("lawChanged") instanceof Map<?, ?> lm) for (var x : lm.entrySet()) s.lawChanged.put(String.valueOf(x.getKey()), ((Number) x.getValue()).intValue());
        }
    }
}
