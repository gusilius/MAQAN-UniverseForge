package dev.worldforge.origins;

import dev.worldforge.mod.Json;
import dev.worldforge.mod.api.DemoApi;
import dev.worldforge.mod.api.StatApi;

import java.util.*;

/**
 * Происхождение, класс и ремесло владельца (игрока "p:..." или NPC "n:..."). Раса выбирается один раз; класс - дерево ступеней:
 * базовый класс, затем улучшения по требованиям (уровень, характеристики, раса, флаги квестов); профессии копят опыт от работы и растут в рангах.
 * Бонусы отдаются в общую шину {@link StatApi}, так что прокачка и другие аддоны учитывают их, ничего не зная об этом аддоне.
 */
public final class Origins {
    public interface Hooks {
        /** Сводка владельца (level и характеристики). По умолчанию из общей шины. */
        default Map<String, Double> sheet(String owner) { return StatApi.sheet(owner); }
        default boolean flag(String owner, String flag) { return false; }
        /** Класс выбрал путь прокачки (архетип) - прокачка выдаст стартовый узел. */
        default void archetype(String owner, String archetype) {}
        /** Государство владельца (для переписи жителей) или "". */
        default String realmOf(String owner) { return ""; }
        /** Свойство государства (races - объявленный состав жителей «human:60,elf:30»). */
        default Object realmProp(String realm, String key) { return null; }
    }

    public static final class Build {
        public String race = "", cls = "";
        public final List<String> history = new ArrayList<>();
        public int retrainDay;
        public final Map<String, Integer> prof = new LinkedHashMap<>();
    }

    public static final int MAX_PROFESSIONS = 2, RETRAIN_DAYS = 30;

    public final OriginDefs defs;
    private final Map<String, Build> builds = new LinkedHashMap<>();
    public Hooks hooks = new Hooks() {};
    public String lastError = "";

    public Origins(OriginDefs defs) { this.defs = defs; }

    /** Public access returns a detached value; callers cannot mutate canonical state through a getter. */
    public synchronized Build build(String owner) { return copy(builds.get(owner)); }

    /** Non-materializing detached lookup. */
    public synchronized Build peek(String owner) { return builds.containsKey(owner) ? copy(builds.get(owner)) : null; }

    private Build state(String owner) {
        Build value = builds.get(owner);
        return value == null ? new Build() : value;
    }

    private static Build copy(Build source) {
        Build out = new Build();
        if (source == null) return out;
        out.race = source.race;
        out.cls = source.cls;
        out.history.addAll(source.history);
        out.retrainDay = source.retrainDay;
        out.prof.putAll(source.prof);
        return out;
    }

    /** Регистрирует поставщика поправок и меток в общей шине. */
    public void publish() {
        StatApi.register("origins", this::mods);
        DemoApi.registerRaces("origins", this::races);
        StatApi.registerTags("origins", o -> {
            Build b = peek(o);
            Map<String, String> t = new LinkedHashMap<>();
            if (b == null) return t;
            if (!b.race.isEmpty()) t.put("race", b.race);
            if (!b.cls.isEmpty()) { t.put("class", b.cls); t.put("class_tier", String.valueOf(defs.classes.get(b.cls) == null ? 0 : defs.classes.get(b.cls).tier())); }
            if (!b.prof.isEmpty()) t.put("profession", b.prof.keySet().iterator().next());
            return t;
        });
    }

    // ---------- демография ----------

    /**
     * Состав жителей государства. Если у государства задано свойство races ("human:60,elf:30") - оно; иначе перепись известных персонажей (игроки и NPC) с этим
     * государством. Каждая подраса засчитывается и своей расе, и всем предкам, поэтому доля «human» включает имперцев и знатных.
     */
    public synchronized Map<String, Double> races(String realm) {
        Map<String, Double> declared = DemoApi.shares(DemoApi.parse(hooks.realmProp(realm, "races")));
        if (!declared.isEmpty()) {
            Map<String, Double> out = new LinkedHashMap<>(declared);
            for (var e : declared.entrySet()) for (OriginDefs.Race r : defs.raceChain(e.getKey())) if (!r.id().equals(e.getKey())) out.merge(r.id(), e.getValue(), Double::sum);
            return out;
        }
        Map<String, Double> out = new LinkedHashMap<>();
        int n = 0;
        for (var e : builds.entrySet()) {
            if (e.getValue().race.isEmpty() || !realm.equals(hooks.realmOf(e.getKey()))) continue;
            n++;
            for (OriginDefs.Race r : defs.raceChain(e.getValue().race)) out.merge(r.id(), 1.0, Double::sum);
        }
        if (n == 0) return Map.of();
        for (var e : out.entrySet()) e.setValue(e.getValue() / n);
        return out;
    }

    /** Раса нового жителя государства: по долям состава (подрасы выбираются по весам внутри родителя). Нет состава - обычные веса рас. */
    public String pickRace(String realm, Random rng) {
        Map<String, Double> w = new LinkedHashMap<>();
        for (var e : DemoApi.shares(DemoApi.parse(hooks.realmProp(realm, "races"))).entrySet()) if (defs.races.containsKey(e.getKey())) w.put(e.getKey(), e.getValue());
        if (w.isEmpty()) return "";
        double t = 0; for (double v : w.values()) t += v;
        double x = rng.nextDouble() * t;
        String pick = w.keySet().iterator().next();
        for (var e : w.entrySet()) { x -= e.getValue(); if (x < 0) { pick = e.getKey(); break; } }
        List<OriginDefs.Race> subs = new ArrayList<>();
        for (OriginDefs.Race r : defs.races.values()) if (r.parent().equals(pick)) subs.add(r);
        if (subs.isEmpty() || rng.nextInt(3) == 0) return pick;
        int tot = 0; for (OriginDefs.Race r : subs) tot += Math.max(1, r.weight());
        int k = rng.nextInt(tot);
        for (OriginDefs.Race r : subs) { k -= Math.max(1, r.weight()); if (k < 0) return r.id(); }
        return pick;
    }

    // ---------- раса ----------

    public synchronized boolean setRace(String owner, String id) {
        if (owner == null || owner.isBlank()) throw new IllegalArgumentException("Пустой идентификатор владельца");
        if (!defs.races.containsKey(id)) { lastError = "Нет такой расы."; return false; }
        Build existing = builds.get(owner);
        if (existing != null && !existing.race.isEmpty()) { lastError = "Раса уже выбрана."; return false; }
        try (var mutation = dev.worldforge.mod.api.WorldForgeApi.revisions().begin(
                dev.worldforge.mod.api.DomainMutationRevisions.Domain.ORIGINS)) {
            Build target = builds.computeIfAbsent(owner, ignored -> new Build());
            target.race = id;
            mutation.commit();
        }
        return true;
    }

    public synchronized void resetRace(String owner) {
        Build existing = builds.get(owner);
        if (existing == null || existing.race.isEmpty()) return;
        try (var mutation = dev.worldforge.mod.api.WorldForgeApi.revisions().begin(
                dev.worldforge.mod.api.DomainMutationRevisions.Domain.ORIGINS)) {
            existing.race = "";
            mutation.commit();
        }
    }

    // ---------- классы ----------

    private String reqProblem(OriginDefs.ClassDef c, String owner) {
        Build b = state(owner);
        Map<String, Double> sh = hooks.sheet(owner);
        double lv = sh.getOrDefault("level", 1.0);
        if (lv < c.minLevel()) return "Нужен уровень " + c.minLevel() + ".";
        for (var e : c.reqStat().entrySet()) if (sh.getOrDefault(e.getKey(), 0.0) < e.getValue()) return "Нужно: " + e.getKey() + " " + e.getValue() + ".";
        if (!defs.raceOk(b.race, c.reqRaces())) return "Только для: " + String.join(", ", c.reqRaces()) + ".";
        for (String f : c.reqFlags()) if (!hooks.flag(owner, f)) return "Нужно выполнить: " + f + ".";
        for (String f : c.banFlags()) if (hooks.flag(owner, f)) return "Несовместимо с вашим состоянием: " + f + ".";
        for (var e : c.reqProf().entrySet()) if (rank(owner, e.getKey()) < e.getValue()) return "Нужна профессия «" + defs.professions.get(e.getKey()).name() + "», звание не ниже " + (e.getValue() + 1) + "-го.";
        if (!c.exclusive().isEmpty()) for (String h : b.history) { var o = defs.classes.get(h); if (o != null && o.exclusive().equals(c.exclusive()) && !o.id().equals(c.id())) return "Несовместимо с выбранным путём: " + o.name() + ".";}
        return null;
    }

    /** Классы, на которые можно перейти или которые видны как цель. Значение - причина отказа или null, если можно сейчас. */
    public synchronized Map<OriginDefs.ClassDef, String> options(String owner) {
        Build b = state(owner);
        Map<OriginDefs.ClassDef, String> out = new LinkedHashMap<>();
        for (OriginDefs.ClassDef c : defs.classes.values()) {
            boolean visible = b.cls.isEmpty() ? c.from().isEmpty() : defs.isParent(c, b.cls);
            if (visible) out.put(c, reqProblem(c, owner));
        }
        return out;
    }

    public synchronized boolean chooseClass(String owner, String id) {
        OriginDefs.ClassDef c = defs.classes.get(id);
        Build b = state(owner);
        if (c == null) { lastError = "Нет такого класса."; return false; }
        if (b.cls.isEmpty() ? !c.from().isEmpty() : !defs.isParent(c, b.cls)) { lastError = "Этот класс не следует из вашего."; return false; }
        String why = reqProblem(c, owner);
        if (why != null) { lastError = why; return false; }
        try (var mutation = dev.worldforge.mod.api.WorldForgeApi.revisions().begin(
                dev.worldforge.mod.api.DomainMutationRevisions.Domain.ORIGINS)) {
            Build target = copy(builds.get(owner));
            target.cls = id; target.history.add(id);
            if (!c.archetype().isEmpty()) hooks.archetype(owner, c.archetype());
            builds.put(owner, target);
            mutation.commit();
        }
        return true;
    }

    /** Переобучение: снимает последний выбранный класс и возвращает к предыдущему. Раз в RETRAIN_DAYS дней. Возвращает снятый класс или null. */
    public synchronized String retrain(String owner, int day) {
        Build b = state(owner);
        if (b.history.isEmpty()) { lastError = "Нечего менять."; return null; }
        if (day - b.retrainDay < RETRAIN_DAYS && b.retrainDay > 0) { lastError = "Переучиваться можно раз в " + RETRAIN_DAYS + " дней (ещё " + (RETRAIN_DAYS - (day - b.retrainDay)) + ")."; return null; }
        try (var mutation = dev.worldforge.mod.api.WorldForgeApi.revisions().begin(
                dev.worldforge.mod.api.DomainMutationRevisions.Domain.ORIGINS)) {
            String gone = b.history.remove(b.history.size() - 1);
            b.cls = b.history.isEmpty() ? "" : b.history.get(b.history.size() - 1);
            b.retrainDay = Math.max(1, day);
            mutation.commit();
            return gone;
        }
    }

    // ---------- профессии ----------

    public synchronized String canLearn(String owner, String id) {
        OriginDefs.Profession p = defs.professions.get(id);
        Build b = state(owner);
        if (p == null) return "Нет такой профессии.";
        if (b.prof.containsKey(id)) return "Уже освоена.";
        if (!p.from().isEmpty()) {
            int pr = rank(owner, p.from());
            if (pr < 0) return "Сначала освойте: " + defs.professions.get(p.from()).name() + ".";
            if (pr < p.minRank()) return "Нужно звание «" + defs.professions.get(p.from()).ranks().get(p.minRank()).name() + "» в профессии " + defs.professions.get(p.from()).name() + ".";
        } else if (rootCount(b) >= MAX_PROFESSIONS) return "Можно владеть не более " + MAX_PROFESSIONS + " профессиями.";
        Map<String, Double> sh = hooks.sheet(owner);
        for (var e : p.reqStat().entrySet()) if (sh.getOrDefault(e.getKey(), 0.0) < e.getValue()) return "Нужно: " + e.getKey() + " " + e.getValue() + ".";
        if (!defs.raceOk(b.race, p.reqRaces())) return "Эта профессия не для вашей расы.";
        if (!p.reqClasses().isEmpty() && b.history.stream().noneMatch(p.reqClasses()::contains)) return "Нужен класс: " + String.join(" / ", p.reqClasses().stream().map(x -> defs.classes.get(x).name()).toList()) + ".";
        if (!p.exclusive().isEmpty()) for (String x : b.prof.keySet()) { var o = defs.professions.get(x); if (o != null && o.exclusive().equals(p.exclusive())) return "Несовместимо с освоенным: " + o.name() + ".";}
        return null;
    }

    private int rootCount(Build b) { int n = 0; for (String id : b.prof.keySet()) { var p = defs.professions.get(id); if (p != null && p.from().isEmpty()) n++; } return n; }

    public synchronized boolean learn(String owner, String id) {
        String why = canLearn(owner, id);
        if (why != null) { lastError = why; return false; }
        try (var mutation = dev.worldforge.mod.api.WorldForgeApi.revisions().begin(
                dev.worldforge.mod.api.DomainMutationRevisions.Domain.ORIGINS)) {
            builds.computeIfAbsent(owner, ignored -> new Build()).prof.put(id, 0);
            mutation.commit();
        }
        return true;
    }

    public synchronized void drop(String owner, String id) {
        Build b = builds.get(owner);
        if (b == null || !b.prof.containsKey(id)) return;
        boolean dependent = b.prof.keySet().stream().anyMatch(x -> { var p = defs.professions.get(x); return p != null && p.from().equals(id); });
        try (var mutation = dev.worldforge.mod.api.WorldForgeApi.revisions().begin(
                dev.worldforge.mod.api.DomainMutationRevisions.Domain.ORIGINS)) {
            b.prof.remove(id);
            b.prof.keySet().removeIf(x -> { var p = defs.professions.get(x); return p != null && p.from().equals(id); });
            if (dependent || !b.prof.containsKey(id)) mutation.commit();
        }
    }

    public synchronized int rank(String owner, String id) {
        Integer xp = state(owner).prof.get(id);
        OriginDefs.Profession p = defs.professions.get(id);
        if (xp == null || p == null) return -1;
        int r = 0;
        for (int i = 0; i < p.ranks().size(); i++) if (xp >= p.ranks().get(i).xp()) r = i;
        return r;
    }

    /** Работа по профессии (units - единиц выполненной работы). Возвращает новый ранг, если он вырос, иначе -1. */
    public synchronized int work(String owner, String id, int units) {
        Build b = state(owner);
        if (!b.prof.containsKey(id) || units <= 0) return -1;
        int before = rank(owner, id);
        int previousXp = b.prof.get(id);
        int nextXp = (int) Math.min(Integer.MAX_VALUE, (long) previousXp + (long) units);
        if (nextXp == previousXp) return -1;
        try (var mutation = dev.worldforge.mod.api.WorldForgeApi.revisions().begin(
                dev.worldforge.mod.api.DomainMutationRevisions.Domain.ORIGINS)) {
            b.prof.put(id, nextXp);
            int after = rank(owner, id);
            mutation.commit();
            return after > before ? after : -1;
        }
    }

    // ---------- поправки ----------

    public synchronized Map<String, Double> mods(String owner) {
        Build b = peek(owner);
        Map<String, Double> out = new LinkedHashMap<>();
        if (b == null) return out;
        double lv = hooks.sheet(owner).getOrDefault("level", 1.0);
        for (OriginDefs.Race r : defs.raceChain(b.race)) {
            r.mods().forEach((k, v) -> out.merge(k, v, Double::sum));
            for (var e : defs.perksOf(r.id()).entrySet()) if (e.getKey() <= lv) e.getValue().forEach((k, v) -> out.merge(k, v, Double::sum));
        }
        for (String h : b.history) {
            OriginDefs.ClassDef c = defs.classes.get(h);
            if (c == null) continue;
            c.mods().forEach((k, v) -> out.merge(k, v, Double::sum));
            for (var e : c.perks().entrySet()) if (e.getKey() <= lv) e.getValue().forEach((k, v) -> out.merge(k, v, Double::sum));
        }
        for (var e : b.prof.entrySet()) {
            OriginDefs.Profession p = defs.professions.get(e.getKey());
            if (p == null) continue;
            int rk = rank(owner, e.getKey());
            for (int i = 0; i <= rk; i++) p.ranks().get(i).mods().forEach((k, v) -> out.merge(k, v, Double::sum));
        }
        for (OriginDefs.Synergy y : activeSynergies(owner)) y.mods().forEach((k, v) -> out.merge(k, v, Double::sum));
        return out;
    }

    /** Переплетения, условия которых сейчас выполнены. */
    public synchronized List<OriginDefs.Synergy> activeSynergies(String owner) {
        List<OriginDefs.Synergy> l = new ArrayList<>();
        Build b = peek(owner);
        if (b == null) return l;
        for (OriginDefs.Synergy y : defs.synergies.values()) {
            if (!y.races().isEmpty() && !defs.raceOk(b.race, y.races())) continue;
            if (!y.classes().isEmpty() && b.history.stream().noneMatch(y.classes()::contains)) continue;
            boolean ok = true;
            for (var e : y.profs().entrySet()) if (rank(owner, e.getKey()) < e.getValue()) { ok = false; break; }
            if (!ok) continue;
            for (String f : y.flags()) if (!hooks.flag(owner, f)) { ok = false; break; }
            if (ok) l.add(y);
        }
        return l;
    }

    // ---------- NPC ----------

    /** Происхождение NPC по сиду. hint - id профессии или группа ("craft", "trade", "military"...); level - для выбора класса. */
    public synchronized Build assign(String owner, long seed, String hint, int level) { return assign(owner, seed, hint, level, ""); }

    /** То же, но раса берётся из состава жителей государства realm (если он известен). */
    public synchronized Build assign(String owner, long seed, String hint, int level, String realm) {
        Build existing = builds.get(owner);
        if (existing != null) return copy(existing); // Repeated NPC population passes preserve progression and expose only a detached value.
        if (owner == null || owner.isBlank()) throw new IllegalArgumentException("Пустой идентификатор владельца");
        Random rng = new Random(seed);
        Build b = new Build();
        try (var mutation = dev.worldforge.mod.api.WorldForgeApi.revisions().begin(
                dev.worldforge.mod.api.DomainMutationRevisions.Domain.ORIGINS)) {
        String forced = realm.isEmpty() ? "" : pickRace(realm, rng);
        int total = 0;
        for (OriginDefs.Race r : defs.races.values()) total += Math.max(1, r.weight());
        if (!forced.isEmpty()) b.race = forced;
        else if (total > 0) {
            int pick = rng.nextInt(total);
            for (OriginDefs.Race r : defs.races.values()) { pick -= Math.max(1, r.weight()); if (pick < 0) { b.race = r.id(); break; } }
        }
        List<OriginDefs.Profession> cand = new ArrayList<>();
        for (OriginDefs.Profession p : defs.professions.values()) if (p.id().equals(hint) || p.group().equals(hint)) cand.add(p);
        if (!cand.isEmpty()) {
            OriginDefs.Profession p = cand.get(rng.nextInt(cand.size()));
            if (p.from().isEmpty() && defs.raceOk(b.race, p.reqRaces())) b.prof.put(p.id(), p.ranks().get(Math.min(p.ranks().size() - 1, level / 12)).xp());
        }
        List<OriginDefs.ClassDef> bases = new ArrayList<>();
        for (OriginDefs.ClassDef c : defs.classes.values()) if (c.from().isEmpty() && defs.raceOk(b.race, c.reqRaces())) bases.add(c);
        if (level >= 5 && !bases.isEmpty()) {
            OriginDefs.ClassDef c = bases.get(rng.nextInt(bases.size()));
            b.cls = c.id(); b.history.add(c.id());
            if (!c.archetype().isEmpty()) hooks.archetype(owner, c.archetype());
            for (int tier = 2; tier <= 3; tier++) {
                List<OriginDefs.ClassDef> next = new ArrayList<>();
                for (OriginDefs.ClassDef n : defs.classes.values()) if (defs.isParent(n, b.cls) && n.minLevel() <= level && defs.raceOk(b.race, n.reqRaces()) && n.reqFlags().isEmpty()) next.add(n);
                if (next.isEmpty()) break;
                OriginDefs.ClassDef n = next.get(rng.nextInt(next.size()));
                b.cls = n.id(); b.history.add(n.id());
            }
        }
        builds.put(owner, b);
        mutation.commit();
        return copy(b);
        }
    }

    // ---------- вид и сохранение ----------

    private static String p2(int rk, OriginDefs.Profession p) { return p.ranks().get(Math.max(0, rk)).name(); }

    public int today;

    public synchronized String viewJson(String owner) {
        Build b = state(owner);
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("race", b.race); root.put("class", b.cls); root.put("history", new ArrayList<Object>(b.history));
        List<Object> opts = new ArrayList<>();
        for (var e : options(owner).entrySet()) opts.add(new LinkedHashMap<String, Object>(Map.of("id", e.getKey().id(), "name", e.getKey().name(), "desc", e.getKey().desc(), "tier", e.getKey().tier(), "why", e.getValue() == null ? "" : e.getValue())));
        root.put("options", opts);
        List<Object> pr = new ArrayList<>();
        for (var e : b.prof.entrySet()) {
            OriginDefs.Profession p = defs.professions.get(e.getKey());
            if (p == null) continue;
            int rk = rank(owner, e.getKey());
            pr.add(new LinkedHashMap<String, Object>(Map.of("id", p.id(), "name", p.name(), "rank", p.ranks().get(rk).name(), "xp", e.getValue(), "next", rk + 1 < p.ranks().size() ? p.ranks().get(rk + 1).xp() : 0)));
        }
        root.put("professions", pr);
        Map<String, Object> mm = new LinkedHashMap<>();
        for (var e : mods(owner).entrySet()) mm.put(e.getKey(), Math.round(e.getValue() * 100) / 100.0);
        root.put("mods", mm);
        // деревья: все классы, расы и профессии со состоянием и причиной отказа
        List<Object> ct = new ArrayList<>();
        Map<OriginDefs.ClassDef, String> opt = options(owner);
        for (OriginDefs.ClassDef c : defs.classes.values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", c.id()); m.put("name", c.name()); m.put("tier", c.tier()); m.put("from", c.from()); m.put("also", new ArrayList<Object>(c.alsoFrom())); m.put("desc", c.desc());
            m.put("excl", c.exclusive());
            String st = b.history.contains(c.id()) ? (b.cls.equals(c.id()) ? "current" : "taken") : opt.containsKey(c) ? (opt.get(c) == null ? "open" : "blocked") : "far";
            m.put("state", st);
            m.put("why", opt.get(c) == null ? "" : opt.get(c));
            Map<String, Object> md = new LinkedHashMap<>();
            for (var e : c.mods().entrySet()) md.put(e.getKey(), e.getValue());
            m.put("mods", md);
            ct.add(m);
        }
        root.put("classTree", ct);
        List<Object> syn = new ArrayList<>();
        for (OriginDefs.Synergy y : activeSynergies(owner)) syn.add(new LinkedHashMap<String, Object>(Map.of("id", y.id(), "name", y.name(), "desc", y.desc())));
        root.put("synergies", syn);
        List<Object> rt = new ArrayList<>();
        for (OriginDefs.Race r : defs.races.values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.id()); m.put("name", r.name()); m.put("parent", r.parent()); m.put("desc", r.desc()); m.put("color", r.color());
            m.put("chosen", r.id().equals(b.race)); m.put("innate", new LinkedHashMap<String, Object>(r.innate()));
            rt.add(m);
        }
        root.put("raceTree", rt);
        List<Object> pt = new ArrayList<>();
        for (OriginDefs.Profession pr2 : defs.professions.values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", pr2.id()); m.put("name", pr2.name()); m.put("from", pr2.from()); m.put("desc", pr2.desc()); m.put("group", pr2.group());
            boolean has = b.prof.containsKey(pr2.id());
            String why = has ? null : canLearn(owner, pr2.id());
            m.put("state", has ? "learned" : why == null ? "open" : "blocked");
            m.put("why", why == null ? "" : why);
            m.put("rank", has ? p2(rank(owner, pr2.id()), pr2) : "");
            pt.add(m);
        }
        root.put("profTree", pt);
        root.put("retrainIn", b.retrainDay > 0 ? Math.max(0, RETRAIN_DAYS - (today - b.retrainDay)) : 0);
        return Json.write(root);
    }

    public synchronized String toJson() { return toJson(builds); }

    private static String toJson(Map<String, Build> source) {
        Map<String, Object> root = new LinkedHashMap<>();
        for (var e : source.entrySet()) {
            Build b = e.getValue();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("race", b.race); m.put("class", b.cls); m.put("history", new ArrayList<Object>(b.history)); m.put("retrain", b.retrainDay); m.put("prof", new LinkedHashMap<String, Object>(b.prof));
            root.put(e.getKey(), m);
        }
        return Json.write(root);
    }

    public synchronized void fromJson(String json) {
        // Build and validate an isolated snapshot before replacing live progression.
        Object parsed = Json.parse(json == null ? "" : json);
        if (!(parsed instanceof Map<?, ?> root))
            throw new IllegalArgumentException("Сохранение происхождения должно быть объектом");
        Map<String, Build> loaded = new LinkedHashMap<>();
        for (var entry : root.entrySet()) {
            if (!(entry.getKey() instanceof String owner) || owner.isBlank())
                throw new IllegalArgumentException("Некорректный идентификатор владельца");
            if (!(entry.getValue() instanceof Map<?, ?> data))
                throw new IllegalArgumentException("Повреждена запись происхождения: " + owner);
            Build b = new Build();
            Object race = data.get("race"), cls = data.get("class");
            if (race != null && !(race instanceof String) || cls != null && !(cls instanceof String))
                throw new IllegalArgumentException("Некорректная раса или класс: " + owner);
            b.race = race == null ? "" : (String) race;
            b.cls = cls == null ? "" : (String) cls;
            // A datapack may temporarily remove an ID. Preserve it as dormant progression
            // instead of rejecting the entire world save; it can become active again on a later reload.
            Object retrain = data.get("retrain");
            if (retrain != null && !(retrain instanceof Number))
                throw new IllegalArgumentException("Некорректный день переобучения: " + owner);
            b.retrainDay = retrain == null ? 0 : ((Number) retrain).intValue();
            if (b.retrainDay < 0) throw new IllegalArgumentException("Отрицательный день переобучения: " + owner);

            Object history = data.get("history");
            if (history != null && !(history instanceof List<?>))
                throw new IllegalArgumentException("Повреждена история классов: " + owner);
            if (history instanceof List<?> items) {
                String previous = "";
                Set<String> unique = new HashSet<>();
                for (Object item : items) {
                    if (!(item instanceof String id) || !unique.add(id))
                        throw new IllegalArgumentException("Повреждена или повторяется ступень класса: " + owner);
                    OriginDefs.ClassDef def = defs.classes.get(id);
                    // Unknown historical class IDs are retained as dormant entries. Known
                    // adjacent definitions must still form a valid progression edge.
                    if (def != null && (previous.isEmpty() ? !def.from().isEmpty() : defs.classes.containsKey(previous) && !defs.isParent(def, previous)))
                        throw new IllegalArgumentException("Нарушена последовательность классов: " + owner + " / " + id);
                    b.history.add(id);
                    previous = id;
                }
            }
            String lastClass = b.history.isEmpty() ? "" : b.history.get(b.history.size() - 1);
            if (!lastClass.equals(b.cls))
                throw new IllegalArgumentException("Текущий класс не совпадает с историей: " + owner);

            Object professions = data.get("prof");
            if (professions != null && !(professions instanceof Map<?, ?>))
                throw new IllegalArgumentException("Повреждены профессии: " + owner);
            if (professions instanceof Map<?, ?> values) for (var p : values.entrySet()) {
                if (!(p.getKey() instanceof String id) || id.isBlank() || !(p.getValue() instanceof Number xp)
                        || xp.longValue() < 0 || xp.longValue() > Integer.MAX_VALUE)
                    throw new IllegalArgumentException("Некорректная профессия: " + owner);
                // Keep removed profession IDs and XP dormant; a later datapack can restore them.
                b.prof.put(id, xp.intValue());
            }
            int roots = 0;
            Set<String> exclusive = new HashSet<>();
            for (String id : b.prof.keySet()) {
                OriginDefs.Profession def = defs.professions.get(id);
                if (def == null) continue; // Removed definition: retain XP without interpreting obsolete rules.
                if (!def.from().isEmpty() && !b.prof.containsKey(def.from()))
                    throw new IllegalArgumentException("Специализация без основной профессии: " + owner + " / " + id);
                if (def.from().isEmpty()) roots++;
                if (!def.exclusive().isEmpty() && !exclusive.add(def.exclusive()))
                    throw new IllegalArgumentException("Взаимоисключающие профессии: " + owner);
            }
            if (roots > MAX_PROFESSIONS)
                throw new IllegalArgumentException("Превышен лимит основных профессий: " + owner);
            loaded.put(owner, b);
        }
        if (toJson().equals(toJson(loaded))) return;
        try (var mutation = dev.worldforge.mod.api.WorldForgeApi.revisions().begin(
                dev.worldforge.mod.api.DomainMutationRevisions.Domain.ORIGINS)) {
            builds.clear();
            builds.putAll(loaded);
            mutation.commit();
        }
    }
}
