package dev.worldforge.progress;

import dev.worldforge.mod.Json;

import java.util.*;
import java.util.function.Function;

/**
 * Движок прокачки. Одинаков для игроков и NPC: опыт -> уровни -> очки (атрибуты, навыки, пассивные умения). Умения дерева берутся от стартового узла
 * по связям. Итоговые статистики складываются из базы, атрибутов, навыков, узлов, уровня и внешних поправок (черты, звания организаций, предметы).
 */
public final class Progress {
    public interface Hooks {
        int gold(String owner);
        boolean takeGold(String owner, int n);
    }

    public static final class Hooks0 implements Hooks {
        public int gold(String o) { return 0; }
        public boolean takeGold(String o, int n) { return false; }
    }

    public final ProgressDefs defs;
    private final Map<String, Profile> profiles = new LinkedHashMap<>();
    public Hooks hooks = new Hooks0();
    /** Внешние поправки владельца (черты NPC, звания организаций, снаряжение). */
    public Function<String, Map<String, Double>> external = dev.worldforge.mod.api.StatApi::mods;
    public String lastError = "";

    public Progress(ProgressDefs defs) { this.defs = defs; }

    /** Регистрирует сводку прокачки (level и все характеристики) в общей шине: по ней другие аддоны проверяют требования. */
    public void publish() {
        dev.worldforge.mod.api.StatApi.registerSheet("progress", o -> {
            Map<String, Double> m = new LinkedHashMap<>(sheet(o, false));
            m.put("level", (double) profile(o).level);
            return m;
        });
    }

    public Profile profile(String owner) { return profiles.computeIfAbsent(owner, Profile::new); }

    public Profile peek(String owner) { return profiles.get(owner); }

    /** Removes a superseded compatibility profile after its data has migrated to the canonical owner key. */
    public boolean removeProfile(String owner) { return profiles.remove(owner) != null; }

    public Collection<Profile> all() { return profiles.values(); }

    // ---------- опыт и уровни ----------

    /** Начисляет опыт (с учётом xp_gain_pct). Возвращает число полученных уровней. */
    public int award(String owner, double xp) {
        Profile p = profile(owner);
        if (p.level >= defs.maxLevel || xp <= 0) return 0;
        long gain = Math.max(1, Math.round(xp * (1 + get(owner, "xp_gain_pct") / 100.0)));
        p.xp += gain; p.totalXp += gain;
        int up = 0;
        while (p.level < defs.maxLevel && p.xp >= defs.xpFor(p.level)) { p.xp -= defs.xpFor(p.level); levelUp(p); up++; }
        if (p.level >= defs.maxLevel) p.xp = 0;
        return up;
    }

    private void levelUp(Profile p) {
        p.level++;
        p.attrFree += defs.attrPoints; p.skillFree += defs.skillPoints; p.passiveFree += defs.passivePoints;
        Map<String, Integer> ms = defs.milestones.get(p.level);
        if (ms != null) { p.attrFree += ms.getOrDefault("attr", 0); p.skillFree += ms.getOrDefault("skill", 0); p.passiveFree += ms.getOrDefault("passive", 0); }
    }

    /** Опыт за врага: растёт, если враг сильнее, и падает до 10%, если он намного слабее. */
    public double killXp(int playerLevel, int mobLevel, double base) {
        double k = 1 + (mobLevel - playerLevel) * 0.12;
        return base * Math.max(0.1, Math.min(2.5, k));
    }

    // ---------- атрибуты и навыки ----------

    public boolean addAttr(String owner, String id, int n) {
        Profile p = profile(owner);
        if (!defs.attrs.containsKey(id)) { lastError = "Нет такого атрибута."; return false; }
        if (n <= 0 || p.attrFree < n) { lastError = "Не хватает очков атрибутов."; return false; }
        p.attrFree -= n; p.attrs.merge(id, n, Integer::sum);
        return true;
    }

    public String canSkill(String owner, String id) {
        Profile p = profile(owner);
        ProgressDefs.Skill s = defs.skills.get(id);
        if (s == null) return "Нет такого навыка.";
        int r = p.skill(id);
        if (r >= s.max()) return "Навык на максимуме.";
        if (p.level < s.minLevel()) return "Нужен уровень " + s.minLevel() + ".";
        for (var e : s.reqAttr().entrySet()) if (value(owner, e.getKey()) < e.getValue()) return "Нужно: " + defs.attrs.get(e.getKey()).name() + " " + e.getValue() + ".";
        for (var e : s.reqSkill().entrySet()) if (p.skill(e.getKey()) < e.getValue()) return "Нужно: " + defs.skills.get(e.getKey()).name() + " " + e.getValue() + ".";
        int cost = s.cost().get(r);
        if (p.skillFree < cost) return "Нужно очков навыков: " + cost + ".";
        return null;
    }

    public boolean learnSkill(String owner, String id) {
        String why = canSkill(owner, id);
        if (why != null) { lastError = why; return false; }
        Profile p = profile(owner);
        p.skillFree -= defs.skills.get(id).cost().get(p.skill(id));
        p.skills.merge(id, 1, Integer::sum);
        return true;
    }

    /** Значение атрибута: база + вложенные очки + внешние поправки на сам атрибут. */
    public double value(String owner, String attr) {
        ProgressDefs.Attr a = defs.attrs.get(attr);
        return a == null ? 0 : get(owner, attr);
    }

    // ---------- дерево пассивок ----------

    public String canNode(String owner, String id) {
        Profile p = profile(owner);
        ProgressDefs.Node n = defs.nodes.get(id);
        if (n == null) return "Нет такого узла.";
        if (p.nodes.contains(id)) return "Уже изучено.";
        if (n.kind().equals("start")) return "Стартовый узел выдаётся архетипом.";
        if (p.level < n.minLevel()) return "Нужен уровень " + n.minLevel() + ".";
        if (p.passiveFree < 1) return "Нет свободных очков умений.";
        boolean adj = false;
        for (String l : n.links()) if (p.nodes.contains(l)) adj = true;
        if (!adj) return "Узел не соединён с вашим деревом.";
        return null;
    }

    public boolean allocate(String owner, String id) {
        String why = canNode(owner, id);
        if (why != null) { lastError = why; return false; }
        Profile p = profile(owner);
        p.passiveFree--; p.nodes.add(id);
        return true;
    }

    /** Выбор архетипа: выдаёт стартовый узел. Меняется только пока ничего не вложено. */
    public boolean chooseArchetype(String owner, String id) {
        Profile p = profile(owner);
        ProgressDefs.Archetype a = defs.archetypes.get(id);
        if (a == null) { lastError = "Нет такого пути."; return false; }
        if (p.spentPassive() > 1 && !p.archetype.equals(id)) { lastError = "Путь уже выбран. Сбросьте умения."; return false; }
        p.nodes.removeIf(n -> defs.nodes.get(n) != null && defs.nodes.get(n).kind().equals("start"));
        p.archetype = id; p.nodes.add(a.start());
        return true;
    }

    private boolean connected(Profile p, Set<String> without) {
        Set<String> alive = new LinkedHashSet<>(p.nodes);
        alive.removeAll(without);
        Set<String> seen = new HashSet<>();
        Deque<String> q = new ArrayDeque<>();
        for (String n : alive) if (defs.nodes.get(n) != null && defs.nodes.get(n).kind().equals("start")) { seen.add(n); q.add(n); }
        while (!q.isEmpty()) for (String l : defs.nodes.get(q.poll()).links()) if (alive.contains(l) && seen.add(l)) q.add(l);
        return seen.size() == alive.size();
    }

    /** Снять узел бесплатно, если остальное дерево остаётся связным со стартом. */
    public boolean refund(String owner, String id) {
        Profile p = profile(owner);
        ProgressDefs.Node n = defs.nodes.get(id);
        if (n == null || !p.nodes.contains(id) || n.kind().equals("start")) { lastError = "Этот узел снять нельзя."; return false; }
        if (!connected(p, Set.of(id))) { lastError = "Узел держит другие: сначала снимите дальние."; return false; }
        p.nodes.remove(id); p.passiveFree++;
        return true;
    }

    public int respecCost(String owner) { Profile p = profile(owner); return p.level * 10 * (1 + p.respecs); }

    /** Полный сброс очков за золото: возвращает все атрибуты, навыки и узлы (старт остаётся). */
    public boolean respec(String owner) {
        Profile p = profile(owner);
        int cost = respecCost(owner);
        if (p.level > 1 && !hooks.takeGold(owner, cost)) { lastError = "Нужно " + cost + " золота."; return false; }
        for (int v : p.attrs.values()) p.attrFree += v;
        p.attrs.clear();
        for (var e : p.skills.entrySet()) for (int r = 0; r < e.getValue(); r++) p.skillFree += defs.skills.get(e.getKey()).cost().get(r);
        p.skills.clear();
        Iterator<String> it = p.nodes.iterator();
        while (it.hasNext()) { String n = it.next(); if (!defs.nodes.get(n).kind().equals("start")) { it.remove(); p.passiveFree++; } }
        p.respecs++;
        return true;
    }

    // ---------- статистики ----------

    public Map<String, Double> sheet(String owner) { return sheet(owner, true); }

    /** withExternal=false - без поправок из общей шины (для сводки, по которой другие аддоны проверяют требования; иначе цикл). */
    public Map<String, Double> sheet(String owner, boolean withExternal) {
        Profile p = profile(owner);
        Map<String, Double> f = new LinkedHashMap<>();
        for (ProgressDefs.Stat s : defs.stats.values()) f.merge(s.id(), s.base(), Double::sum);
        for (ProgressDefs.Attr a : defs.attrs.values()) {
            int pts = a.base() + p.attr(a.id());
            f.merge(a.id(), (double) pts, Double::sum);
            for (var e : a.perPoint().entrySet()) f.merge(e.getKey(), e.getValue() * pts, Double::sum);
        }
        for (var e : p.skills.entrySet()) { ProgressDefs.Skill s = defs.skills.get(e.getKey()); if (s != null) for (var m : s.perRank().entrySet()) f.merge(m.getKey(), m.getValue() * e.getValue(), Double::sum); }
        for (String n : p.nodes) { ProgressDefs.Node nd = defs.nodes.get(n); if (nd != null) for (var m : nd.mods().entrySet()) f.merge(m.getKey(), m.getValue(), Double::sum); }
        for (var m : defs.perLevel.entrySet()) f.merge(m.getKey(), m.getValue() * (p.level - 1), Double::sum);
        if (withExternal) for (var m : external.apply(owner).entrySet()) f.merge(m.getKey(), m.getValue(), Double::sum);
        Map<String, Double> out = new LinkedHashMap<>();
        for (var e : f.entrySet()) {
            String k = e.getKey();
            if (k.endsWith("_pct")) { out.put(k, e.getValue()); continue; }
            out.put(k, e.getValue() * (1 + f.getOrDefault(k + "_pct", 0.0) / 100.0));
        }
        return out;
    }

    public double get(String owner, String stat) { return sheet(owner).getOrDefault(stat, 0.0); }

    // ---------- автосборка NPC ----------

    /** Создаёт NPC заданного уровня по архетипу: очки тратятся по весам, узлы берутся выгодными путями. Детерминированно по сиду. */
    public Profile autoBuild(String owner, String archetype, int level, long seed) {
        Profile p = new Profile(owner);
        profiles.put(owner, p);
        ProgressDefs.Archetype a = defs.archetypes.get(archetype);
        if (a == null && !defs.archetypes.isEmpty()) a = defs.archetypes.values().iterator().next();
        if (a == null) return p;
        for (int i = 1; i < Math.max(1, Math.min(level, defs.maxLevel)); i++) levelUp(p);
        p.level = Math.max(1, Math.min(level, defs.maxLevel));
        p.archetype = a.id(); p.nodes.add(a.start());
        Random rng = new Random(seed);
        // атрибуты: по очку в лучший по весам, с небольшим шумом
        List<String> ids = new ArrayList<>(defs.attrs.keySet());
        while (p.attrFree > 0 && !ids.isEmpty()) {
            String best = ids.get(0); double bs = -1e9;
            for (String id : ids) { double s = a.attrWeights().getOrDefault(id, 0.2) / (1 + p.attr(id) * 0.35) + rng.nextDouble() * 0.15; if (s > bs) { bs = s; best = id; } }
            p.attrFree--; p.attrs.merge(best, 1, Integer::sum);
        }
        // навыки
        for (int guard = 0; guard < 200 && p.skillFree > 0; guard++) {
            String best = null; double bs = 0;
            for (ProgressDefs.Skill s : defs.skills.values()) {
                if (canSkill(owner, s.id()) != null) continue;
                double sc = a.skillWeights().getOrDefault(s.id(), 0.1) / s.cost().get(p.skill(s.id())) + rng.nextDouble() * 0.05;
                if (sc > bs) { bs = sc; best = s.id(); }
            }
            if (best == null) break;
            learnSkill(owner, best);
        }
        // пассивки: жадно по ценности узла на единицу пути
        for (int guard = 0; guard < 400 && p.passiveFree > 0; guard++) {
            Map<String, String> prev = new HashMap<>();
            Map<String, Integer> dist = new HashMap<>();
            Deque<String> q = new ArrayDeque<>();
            for (String n : p.nodes) { dist.put(n, 0); q.add(n); }
            while (!q.isEmpty()) {
                String c = q.poll();
                for (String l : defs.nodes.get(c).links()) {
                    ProgressDefs.Node nd = defs.nodes.get(l);
                    if (nd == null || dist.containsKey(l) || nd.minLevel() > p.level) continue;
                    dist.put(l, dist.get(c) + 1); prev.put(l, c); q.add(l);
                }
            }
            String best = null; double bs = 0;
            for (var e : dist.entrySet()) {
                if (e.getValue() == 0 || e.getValue() > p.passiveFree) continue;
                double val = 0;
                for (var m : defs.nodes.get(e.getKey()).mods().entrySet()) val += a.weights().getOrDefault(m.getKey(), 0.0) * Math.abs(m.getValue());
                if (defs.nodes.get(e.getKey()).kind().equals("keystone")) val *= 0.6;
                double sc = val / e.getValue() + rng.nextDouble() * 0.05;
                if (sc > bs) { bs = sc; best = e.getKey(); }
            }
            if (best == null) break;
            LinkedList<String> path = new LinkedList<>();
            for (String c = best; c != null && !p.nodes.contains(c); c = prev.get(c)) path.addFirst(c);
            for (String n : path) { p.passiveFree--; p.nodes.add(n); }
        }
        return p;
    }

    // ---------- вид для окна ----------

    public String viewJson(String owner) {
        Profile p = profile(owner);
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("level", p.level); root.put("xp", p.xp); root.put("next", p.level >= defs.maxLevel ? 0 : defs.xpFor(p.level)); root.put("max", defs.maxLevel);
        root.put("attrFree", p.attrFree); root.put("skillFree", p.skillFree); root.put("passiveFree", p.passiveFree); root.put("archetype", p.archetype);
        root.put("respec", respecCost(owner));
        Map<String, Object> at = new LinkedHashMap<>();
        for (ProgressDefs.Attr a : defs.attrs.values()) at.put(a.id(), p.attr(a.id()));
        root.put("attrs", at);
        root.put("skills", new LinkedHashMap<String, Object>(p.skills));
        root.put("nodes", new ArrayList<Object>(p.nodes));
        Map<String, Object> sh = new LinkedHashMap<>();
        for (var e : sheet(owner).entrySet()) sh.put(e.getKey(), Math.round(e.getValue() * 100) / 100.0);
        root.put("sheet", sh);
        List<Object> can = new ArrayList<>();
        for (String id : defs.nodes.keySet()) if (canNode(owner, id) == null) can.add(id);
        root.put("allocatable", can);
        Map<String, Object> sk = new LinkedHashMap<>();
        for (String id : defs.skills.keySet()) { String why = canSkill(owner, id); sk.put(id, why == null ? "" : why); }
        root.put("skillWhy", sk);
        return Json.write(root);
    }

    /** Статичная часть: определения для окна (шлётся один раз). */
    public String defsJson() {
        Map<String, Object> root = new LinkedHashMap<>();
        List<Object> at = new ArrayList<>();
        for (ProgressDefs.Attr a : defs.attrs.values()) at.add(new LinkedHashMap<String, Object>(Map.of("id", a.id(), "name", a.name(), "group", a.group(), "desc", a.desc(), "color", a.color(), "base", a.base(), "per", new LinkedHashMap<String, Object>(a.perPoint()))));
        root.put("attrs", at);
        List<Object> sk = new ArrayList<>();
        for (ProgressDefs.Skill s : defs.skills.values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", s.id()); m.put("name", s.name()); m.put("group", s.group()); m.put("desc", s.desc()); m.put("max", s.max()); m.put("cost", new ArrayList<Object>(s.cost()));
            m.put("level", s.minLevel()); m.put("per", new LinkedHashMap<String, Object>(s.perRank()));
            sk.add(m);
        }
        root.put("skills", sk);
        List<Object> nd = new ArrayList<>();
        for (ProgressDefs.Node n : defs.nodes.values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", n.id()); m.put("name", n.name()); m.put("kind", n.kind()); m.put("desc", n.desc()); m.put("x", n.x()); m.put("y", n.y()); m.put("level", n.minLevel());
            m.put("mods", new LinkedHashMap<String, Object>(n.mods())); m.put("links", new ArrayList<Object>(n.links()));
            nd.add(m);
        }
        root.put("nodes", nd);
        List<Object> ar = new ArrayList<>();
        for (ProgressDefs.Archetype a : defs.archetypes.values()) ar.add(new LinkedHashMap<String, Object>(Map.of("id", a.id(), "name", a.name(), "desc", a.desc(), "start", a.start())));
        root.put("archetypes", ar);
        List<Object> st = new ArrayList<>();
        for (ProgressDefs.Stat s : defs.stats.values()) st.add(new LinkedHashMap<String, Object>(Map.of("id", s.id(), "name", s.name(), "fmt", s.fmt())));
        root.put("stats", st);
        return Json.write(root);
    }

    // ---------- сохранение ----------

    public String toJson() {
        List<Object> ps = new ArrayList<>();
        for (Profile p : profiles.values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("o", p.owner); m.put("lv", p.level); m.put("xp", p.xp); m.put("txp", p.totalXp); m.put("af", p.attrFree); m.put("sf", p.skillFree); m.put("pf", p.passiveFree);
            m.put("arch", p.archetype); m.put("attrs", new LinkedHashMap<String, Object>(p.attrs)); m.put("skills", new LinkedHashMap<String, Object>(p.skills)); m.put("nodes", new ArrayList<Object>(p.nodes)); m.put("rs", p.respecs);
            ps.add(m);
        }
        return Json.write(new LinkedHashMap<String, Object>(Map.of("profiles", ps)));
    }

    private static int n(Object o) { return o instanceof Number x ? x.intValue() : 0; }

    public void fromJson(String json) {
        profiles.clear();
        if (!(Json.parse(json) instanceof Map<?, ?> root) || !(root.get("profiles") instanceof List<?> l)) return;
        for (Object o : l) {
            if (!(o instanceof Map<?, ?> m)) continue;
            Profile p = new Profile(String.valueOf(m.get("o")));
            p.level = Math.max(1, n(m.get("lv"))); p.xp = n(m.get("xp")); p.totalXp = ((Number) (m.get("txp") == null ? 0 : m.get("txp"))).longValue();
            p.attrFree = n(m.get("af")); p.skillFree = n(m.get("sf")); p.passiveFree = n(m.get("pf")); p.archetype = String.valueOf(m.get("arch") == null ? "" : m.get("arch")); p.respecs = n(m.get("rs"));
            if (m.get("attrs") instanceof Map<?, ?> a) for (var e : a.entrySet()) p.attrs.put(String.valueOf(e.getKey()), n(e.getValue()));
            if (m.get("skills") instanceof Map<?, ?> a) for (var e : a.entrySet()) p.skills.put(String.valueOf(e.getKey()), n(e.getValue()));
            if (m.get("nodes") instanceof List<?> a) for (Object x : a) p.nodes.add(String.valueOf(x));
            profiles.put(p.owner, p);
        }
    }
}
