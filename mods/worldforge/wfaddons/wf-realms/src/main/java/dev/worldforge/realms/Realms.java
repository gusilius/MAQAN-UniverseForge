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
        /** Normalized, live political pressures supplied by the owning game systems (0..1). */
        default Map<String, Double> policySignals(String realm) { return Map.of(); }
        default int today() { return 0; }
    }

    public static final class State {
        public String gov = "";
        public double points;
        public int govChanged = -9999;
        public final Set<String> nodes = new LinkedHashSet<>();
        public final Map<String, String> laws = new LinkedHashMap<>();
        public final Map<String, Integer> lawChanged = new HashMap<>();
        /** Signal snapshot at the time each law was adopted; used to evaluate observed outcomes. */
        public final Map<String, Map<String, Double>> lawSignalBaselines = new LinkedHashMap<>();
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
        s.lawSignalBaselines.clear();
        s.nodes.removeIf(k -> { RealmDefs.Node n = defs.node(k); return n != null && !nodeGovOk(n, id); });
        return true;
    }

    /** Лучший доступный строй для ИИ-государства: по подгонке, с детерминированной случайностью. */
    public String suggest(String realm, long seed) {
        Random rng = new Random(seed);
        Map<String, Double> signals = hooks.policySignals(realm);
        String best = "";
        double bs = -1e9;
        for (RealmDefs.Gov g : defs.govs.values()) {
            if (!why(realm, g).isEmpty()) continue;
            double sc = fit(realm, g) + policyUtility(g.mods(), signals) * 0.35 + rng.nextDouble() * 5;
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
        Map<String, Double> baseline = new LinkedHashMap<>();
        Map<String, Double> currentSignals = hooks.policySignals(realm);
        for (String key : List.of("unrest", "fiscal_stress", "vassal_discontent", "faction_pressure", "court_intrigue"))
            baseline.put(key, signal(currentSignals, key));
        s.lawSignalBaselines.put(law, baseline);
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

    private static double signal(Map<String, Double> signals, String key) {
        if (signals == null) return 0;
        double value = signals.getOrDefault(key, 0.0);
        return Double.isFinite(value) ? Math.max(0, Math.min(1, value)) : 0;
    }

    /**
     * Scores a policy by its actual effects against the realm's live pressures.
     * Signals are supplied by wf-war, wf-court, Statecraft and the shared map/demography APIs;
     * this class does not create a parallel war, treasury, population or unrest model.
     */
    private static double policyUtility(Map<String, Double> effects, Map<String, Double> signals) {
        double war = signal(signals, "war");
        double fiscal = signal(signals, "fiscal_stress");
        double vassal = signal(signals, "vassal_discontent");
        double trade = signal(signals, "trade_access");
        double diversity = signal(signals, "cultural_diversity");
        double unrest = signal(signals, "unrest");
        double faction = signal(signals, "faction_pressure");
        double intrigue = signal(signals, "court_intrigue");
        double dynastic = signal(signals, "dynastic_ties");
        double councilAccept = signal(signals, "council_ai_accept");
        double score = 0;
        score += effects.getOrDefault("levy_pct", 0.0) * (0.01 + war * 1.50);
        score += effects.getOrDefault("melee_dmg_pct", 0.0) * war * 1.20;
        score += effects.getOrDefault("ranged_dmg_pct", 0.0) * war * 0.80;
        score += effects.getOrDefault("siege_pct", 0.0) * war * 1.35;
        score += effects.getOrDefault("defense_pct", 0.0) * war * 0.70;
        score -= effects.getOrDefault("upkeep_pct", 0.0) * (war * 0.50 + fiscal * 0.35);
        score += effects.getOrDefault("tax_pct", 0.0) * (0.10 + fiscal * 1.20 - vassal * 0.25 - unrest * 0.20 - faction * 0.65);
        score += effects.getOrDefault("trade_pct", 0.0) * (0.15 + trade * 1.40);
        score += effects.getOrDefault("discount_pct", 0.0) * trade * 0.70;
        score += effects.getOrDefault("opinion", 0.0) * (0.20 + vassal * 1.40 + unrest * 1.30 + faction * 1.20);
        score += effects.getOrDefault("vassal_opinion", 0.0) * (vassal * 1.60 + faction * 1.80);
        score -= effects.getOrDefault("unrest_pct", 0.0) * (vassal + unrest);
        score += effects.getOrDefault("xp_gain_pct", 0.0) * diversity * 1.50;
        score += effects.getOrDefault("heal_pct", 0.0) * diversity * 0.20;
        score += effects.getOrDefault("cultural_tolerance_pct", 0.0) * diversity * 2.0;
        score += effects.getOrDefault("plot_defense", 0.0) * intrigue * 1.10;
        score += effects.getOrDefault("ai_accept", 0.0) * (0.15 + dynastic * 0.80 + councilAccept * 0.60);
        score += effects.getOrDefault("trade_pct", 0.0) * dynastic * 0.25;
        score += effects.getOrDefault("doctrine_pct", 0.0) * (0.20 + fiscal * 0.20);
        return score;
    }

    private RealmDefs.Node bestAvailableDoctrine(String realm, Map<String, Double> signals) {
        State state = state(realm);
        if (state.nodes.size() >= doctrineSlots(realm)) return null;
        List<RealmDefs.Node> candidates = new ArrayList<>();
        for (RealmDefs.Branch branch : defs.branches.values())
            for (RealmDefs.Node node : branch.nodes())
                if (canUnlock(realm, node.key())) candidates.add(node);
        candidates.sort(Comparator
                .comparingDouble((RealmDefs.Node node) -> policyUtility(node.mods(), signals) - node.cost() * 0.10)
                .reversed()
                .thenComparing(RealmDefs.Node::key));
        return candidates.isEmpty() ? null : candidates.get(0);
    }

    /** A policy is not judged only by its intended modifier: the AI also remembers what happened after adoption. */
    private static double observedOutcomeAdjustment(State state, String lawId, RealmDefs.LawOption option,
                                                    Map<String, Double> signals) {
        if (!option.id().equals(state.laws.get(lawId))) return 0;
        Map<String, Double> baseline = state.lawSignalBaselines.get(lawId);
        if (baseline == null || baseline.isEmpty()) return 0;
        double change = 0;
        change += (signal(signals, "unrest") - baseline.getOrDefault("unrest", 0.0)) * 22.0;
        change += (signal(signals, "fiscal_stress") - baseline.getOrDefault("fiscal_stress", 0.0)) * 18.0;
        change += (signal(signals, "vassal_discontent") - baseline.getOrDefault("vassal_discontent", 0.0)) * 24.0;
        change += (signal(signals, "faction_pressure") - baseline.getOrDefault("faction_pressure", 0.0)) * 28.0;
        change += (signal(signals, "court_intrigue") - baseline.getOrDefault("court_intrigue", 0.0)) * 12.0;
        return Math.max(-35.0, Math.min(35.0, -change));
    }

    private RealmDefs.LawOption bestLawOption(String realm, RealmDefs.Law law, Map<String, Double> signals) {
        State state = state(realm);
        RealmDefs.LawOption best = null;
        double bestScore = -Double.MAX_VALUE;
        for (RealmDefs.LawOption option : law.options()) {
            if (level(realm) < option.minLevel()) continue;
            double score = policyUtility(option.mods(), signals)
                    + observedOutcomeAdjustment(state, law.id(), option, signals);
            if (best == null || score > bestScore || score == bestScore && option.id().compareTo(best.id()) < 0) {
                best = option;
                bestScore = score;
            }
        }
        return best;
    }

    /**
     * Autonomous government development is strategic rather than cheapest-first/random:
     * choose a regime compatible with population/terrain, invest in the most useful available
     * doctrine, and select laws that answer current war, fiscal, trade and vassal pressures.
     * All effects still flow through the existing Realms modifiers and Statecraft/Treasury APIs.
     */
    public void autoDevelop(String realm, long seed) {
        State state = state(realm);
        Map<String, Double> signals = hooks.policySignals(realm);
        if (state.gov.isEmpty()) {
            String government = suggest(realm, seed);
            if (!government.isEmpty()) setGov(realm, government, true);
        }
        tick(realm);

        while (state.nodes.size() < doctrineSlots(realm)) {
            RealmDefs.Node next = bestAvailableDoctrine(realm, signals);
            if (next == null || !unlock(realm, next.key())) break;
        }

        for (RealmDefs.Law law : defs.laws.values()) {
            boolean alreadyActive = state.laws.containsKey(law.id());
            if (!alreadyActive && state.laws.size() >= lawSlots(realm)) continue;
            RealmDefs.LawOption option = bestLawOption(realm, law, signals);
            if (option == null || option.id().equals(state.laws.get(law.id()))) continue;
            // setLaw enforces the law's own cooldown; changing pressure cannot bypass it.
            setLaw(realm, law.id(), option.id());
        }
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
        root.put("policyMemory", new LinkedHashMap<>(s.lawSignalBaselines));
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
            m.put("lawSignalBaselines", new LinkedHashMap<>(s.lawSignalBaselines));
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
            if (m.get("lawSignalBaselines") instanceof Map<?, ?> baselines) for (var lawEntry : baselines.entrySet()) {
                if (!(lawEntry.getValue() instanceof Map<?, ?> values)) continue;
                Map<String, Double> snapshot = new LinkedHashMap<>();
                for (var value : values.entrySet()) if (value.getValue() instanceof Number n)
                    snapshot.put(String.valueOf(value.getKey()), Math.max(0, Math.min(1, n.doubleValue())));
                s.lawSignalBaselines.put(String.valueOf(lawEntry.getKey()), snapshot);
            }
        }
    }
}
