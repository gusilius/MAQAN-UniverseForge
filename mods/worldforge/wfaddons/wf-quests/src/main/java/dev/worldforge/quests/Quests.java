package dev.worldforge.quests;

import dev.worldforge.mod.Json;
import dev.worldforge.mod.api.ContentApi;
import dev.worldforge.mod.api.StatApi;

import java.util.*;

/**
 * Движок заданий и событий. Кто угодно может дать задание: правитель, чиновник, военный, глава ордена, простой житель, доска объявлений;
 * сложность и награда растут с рангом дающего и уровнем игрока. Задания идут цепочками (requires.quests), ветвятся выборами и меняют мир:
 * репутацию, отношения, влияние в ордене, права на создание своего. События мира (налёт, неурожай, посольство...) приходят в государства,
 * ИИ решает их сам, правитель-игрок выбирает вариант. Всё без Minecraft: инвентарь, золото, опыт, карта - через {@link Hooks}.
 */
public final class Quests {
    public interface Hooks {
        int count(String player, String item);
        boolean take(String player, String item, int n);
        void give(String player, String item, int n);
        void xp(String player, int n);
        int level(String player);
        void rep(String player, String target, int n);
        void relation(String player, String npc, int delta);
        void influence(String player, String org, int n);
        int orgRank(String player, String org);
        String realmOf(String player);
        /** Игрок, правящий государством, или "". */
        String rulerOf(String realm);
        boolean atWar(String realm);
        List<String> neighbors(String realm);
        List<String> enemies(String realm);
        String run(String player, String action, String realm, List<String> args);
        void news(String text);
        void notify(String player, String text);
        void realmProp(String realm, String key, double delta);
        void treasury(String realm, double delta);
        /** Потрясение рынка государства (wf_trade): множитель выпуска товара (или "*" - всех) на число дней. */
        default void shock(String realm, String good, double factor, int days) { }
    }

    public static class Hooks0 implements Hooks {
        public int count(String p, String i) { return 0; }
        public boolean take(String p, String i, int n) { return false; }
        public void give(String p, String i, int n) {}
        public void xp(String p, int n) {}
        public int level(String p) { return 1; }
        public void rep(String p, String t, int n) {}
        public void relation(String p, String npc, int d) {}
        public void influence(String p, String o, int n) {}
        public int orgRank(String p, String o) { return -1; }
        public String realmOf(String p) { return ""; }
        public String rulerOf(String r) { return ""; }
        public boolean atWar(String r) { return false; }
        public List<String> neighbors(String r) { return List.of(); }
        public List<String> enemies(String r) { return List.of(); }
        public String run(String p, String a, String r, List<String> args) { return null; }
        public void news(String t) {}
        public void notify(String p, String t) {}
        public void realmProp(String r, String k, double d) {}
        public void treasury(String r, double d) {}
    }

    /** Собеседник: id, имя, роли, организация, государство. Вид (kinds) определяет, какие задания он может дать. */
    public record Giver(String npc, String name, Set<String> roles, String org, String orgKind, String realm) {
        public static Giver board(String realm) { return new Giver("board:" + realm, "Доска объявлений", Set.of(), "", "", realm); }

        public List<String> kinds() {
            List<String> k = new ArrayList<>();
            if (npc.startsWith("board:")) k.add("board");
            if (roles.contains("ruler")) k.add("ruler");
            for (String r : roles) if (Set.of("chancellor", "steward", "spymaster", "castellan").contains(r)) { if (!k.contains("official")) k.add("official"); k.add("official:" + r); }
            if (roles.contains("marshal") || orgKind.equals("watch") || orgKind.equals("knights")) k.add("military");
            if (!org.isEmpty()) { k.add("org"); k.add("org:" + org); if (!orgKind.isEmpty()) k.add("orgkind:" + orgKind); }
            if (k.isEmpty() || (roles.isEmpty() && org.isEmpty() && !npc.startsWith("board:"))) k.add("villager");
            k.add("any");
            return k;
        }
    }

    public static final class Active {
        public int id, accepted, deadline, waitUntil, progress;
        public String qid, giver, giverName, realm, step = "";
        public final Map<String, String> vars = new LinkedHashMap<>();
        public final List<String> log = new ArrayList<>();
        public int level;
    }

    public record Offer(String key, String qid, Map<String, String> vars, String text, String name, int tier) {}

    public static final class Fired {
        public int id, day, expires;
        public String event, realm, owner = "", text, name;
        public boolean resolved;
        public String choice = "";
    }

    public static final int MAX_ACTIVE = 8, WINDOW = 3, MAX_OFFERS = 3, MAX_FIRED = 60;

    public final QuestDefs defs;
    public Hooks hooks = new Hooks0();
    public String lastError = "";
    private final Map<String, List<Active>> active = new LinkedHashMap<>();
    private final Map<String, Map<String, Integer>> done = new LinkedHashMap<>();       // игрок -> задание -> день последнего завершения
    private final Map<String, Integer> doneCount = new LinkedHashMap<>();
    private final Map<String, Set<String>> flags = new LinkedHashMap<>(), rights = new LinkedHashMap<>();
    private final Map<String, Set<String>> posted = new LinkedHashMap<>();             // государство -> задания, висящие на доске
    private final Map<String, Integer> eventLast = new LinkedHashMap<>();
    public final List<Fired> fired = new ArrayList<>();
    public final List<String> chronicle = new ArrayList<>();
    private int nextId = 1, nextFired = 1;

    public Quests(QuestDefs defs) { this.defs = defs; }

    private boolean fail(String m) { lastError = m; return false; }

    // ---------- флаги и права ----------

    public Set<String> flags(String p) { return flags.computeIfAbsent(p, k -> new LinkedHashSet<>()); }
    public Set<String> rights(String p) { return rights.computeIfAbsent(p, k -> new LinkedHashSet<>()); }
    public boolean hasRight(String p, String r) { return rights(p).contains(r); }
    public boolean isDone(String p, String qid) { return done.getOrDefault(p, Map.of()).containsKey(qid); }
    public int doneCount(String p) { return doneCount.getOrDefault(p, 0); }
    public List<Active> activeOf(String p) { return active.computeIfAbsent(p, k -> new ArrayList<>()); }

    /** Метки игрока для требований: класс, раса из общей шины. */
    private boolean tagIs(String p, String k, Object v) { return String.valueOf(v).equals(StatApi.tag(p, k)); }

    // ---------- условия ----------

    @SuppressWarnings("unchecked")
    public boolean ok(String p, String realm, Map<String, Object> req) {
        for (var e : req.entrySet()) {
            Object v = e.getValue();
            switch (e.getKey()) {
                case "quests" -> { for (Object o : (List<Object>) v) if (!isDone(p, String.valueOf(o))) return false; }
                case "not_quests" -> { for (Object o : (List<Object>) v) if (isDone(p, String.valueOf(o))) return false; }
                case "flags" -> { for (Object o : (List<Object>) v) if (!flags(p).contains(String.valueOf(o))) return false; }
                case "not_flags" -> { for (Object o : (List<Object>) v) if (flags(p).contains(String.valueOf(o))) return false; }
                case "rights" -> { for (Object o : (List<Object>) v) if (!hasRight(p, String.valueOf(o))) return false; }
                case "war" -> { if (Boolean.TRUE.equals(v) != hooks.atWar(realm)) return false; }
                case "ruler" -> { if (Boolean.TRUE.equals(v) != hooks.realmOf(p).equals(realm) && !realm.isEmpty()) return false; }
                case "level_min" -> { if (hooks.level(p) < ((Number) v).intValue()) return false; }
                case "class" -> { if (!tagIs(p, "class", v)) return false; }
                case "race" -> { if (!tagIs(p, "race", v)) return false; }
                case "gold" -> { if (hooks.count(p, "gold") < ((Number) v).intValue()) return false; }
                case "org" -> { if (hooks.orgRank(p, String.valueOf(v)) < 0) return false; }
                case "org_rank" -> { Map<String, Object> m = (Map<String, Object>) v; if (hooks.orgRank(p, String.valueOf(m.get("org"))) < ((Number) m.getOrDefault("min", 0)).intValue()) return false; }
                default -> { }
            }
        }
        return true;
    }

    // ---------- предложения ----------

    private static long seed(String a, String b, int c) { return ((long) a.hashCode() * 31 + b.hashCode()) * 1000003L + c; }

    private String sub(String s, Map<String, String> v) {
        String out = s;
        for (var e : v.entrySet()) out = out.replace("{" + e.getKey() + "}", e.getValue());
        return out;
    }

    /** Жеребьёвка параметров: список - выбор, {"range":[a,b]} - число, "$neighbor"/"$enemy"/"$realm" - из мира. null, если подставить нечего. */
    @SuppressWarnings("unchecked")
    private Map<String, String> resolve(QuestDefs.Quest q, Random rng, String p, Giver g, int level) {
        Map<String, String> out = new LinkedHashMap<>();
        out.put("giver", g.name()); out.put("realm", g.realm()); out.put("tier", String.valueOf(q.tier()));
        for (var e : q.params().entrySet()) {
            Object v = e.getValue();
            String r;
            if (v instanceof List<?> l) r = l.isEmpty() ? "" : String.valueOf(l.get(rng.nextInt(l.size())));
            else if (v instanceof Map<?, ?> m && m.get("range") instanceof List<?> rg && rg.size() == 2) {
                int a = ((Number) rg.get(0)).intValue(), b = ((Number) rg.get(1)).intValue();
                double k = "level".equals(m.get("scale")) ? 1 + 0.04 * (level - 1) : 1;
                r = String.valueOf((int) Math.max(1, Math.round((a + rng.nextInt(Math.max(1, b - a + 1))) * k)));
            } else {
                String s = String.valueOf(v);
                List<String> pool = switch (s) { case "$neighbor" -> hooks.neighbors(g.realm()); case "$enemy" -> hooks.enemies(g.realm()); default -> null; };
                if (pool != null) r = pool.isEmpty() ? "" : pool.get(rng.nextInt(pool.size()));
                else r = s.equals("$realm") ? g.realm() : s;
            }
            if (r.isEmpty()) return null;
            out.put(e.getKey(), r);
        }
        return out;
    }

    private boolean kindMatch(QuestDefs.Quest q, Giver g) {
        List<String> k = g.kinds();
        for (String x : q.giver()) if (k.contains(x)) return true;
        return false;
    }

    /** Можно ли предложить это задание игроку у этого собеседника (без жеребьёвки параметров). */
    public boolean available(String p, QuestDefs.Quest q, Giver g, int day) {
        int lv = hooks.level(p);
        if (lv < q.minLevel() || lv > q.maxLevel()) return false;
        if (!kindMatch(q, g)) return false;
        if (!g.org().isEmpty() && !q.giver().isEmpty() && q.giver().stream().allMatch(k -> k.startsWith("org")) && hooks.orgRank(p, g.org()) < 0) return false;
        for (Active a : activeOf(p)) if (a.qid.equals(q.id())) return false;
        Integer last = done.getOrDefault(p, Map.of()).get(q.id());
        if (last != null && (q.repeat() <= 0 || day - last < q.repeat())) return false;
        if (q.author() != null && q.author().equals(p)) return false;
        return ok(p, g.realm(), q.requires());
    }

    /** До трёх предложений: продолжения цепочек идут первыми, остальное взвешенно. Набор меняется раз в WINDOW дней, внутри окна он стабилен. */
    public List<Offer> offers(String p, Giver g, int day) {
        int win = day / WINDOW;
        Random rng = new Random(seed(p, g.npc(), win));
        List<QuestDefs.Quest> pool = new ArrayList<>();
        for (QuestDefs.Quest q : defs.quests.values()) if (available(p, q, g, day)) pool.add(q);
        List<Offer> out = new ArrayList<>();
        List<QuestDefs.Quest> chain = new ArrayList<>();
        for (QuestDefs.Quest q : pool) if (q.requires().containsKey("quests")) chain.add(q);
        pool.removeAll(chain);
        int cap = g.kinds().contains("villager") ? 2 : MAX_OFFERS;
        List<QuestDefs.Quest> pick = new ArrayList<>(chain.subList(0, Math.min(chain.size(), 2)));
        while (pick.size() < cap && !pool.isEmpty()) {
            int total = 0;
            for (QuestDefs.Quest q : pool) total += q.weight();
            int r = rng.nextInt(total);
            for (QuestDefs.Quest q : pool) { r -= q.weight(); if (r < 0) { pick.add(q); pool.remove(q); break; } }
        }
        int lv = hooks.level(p);
        for (QuestDefs.Quest q : pick) {
            long s = rng.nextLong() & 0xFFFFFFL;
            Map<String, String> v = resolve(q, new Random(s), p, g, lv);
            if (v == null) continue;
            out.add(new Offer(q.id() + "#" + s, q.id(), v, sub(q.desc().isEmpty() ? q.name() : q.desc(), v), q.name(), q.tier()));
        }
        return out;
    }

    public Active accept(String p, Giver g, String key, int day) {
        Offer o = null;
        for (Offer x : offers(p, g, day)) if (x.key().equals(key)) o = x;
        if (o == null) { fail("Это предложение уже недоступно."); return null; }
        if (activeOf(p).size() >= MAX_ACTIVE) { fail("Слишком много заданий (" + MAX_ACTIVE + ")."); return null; }
        return start(p, defs.quests.get(o.qid()), g, o.vars(), day);
    }

    /** Начинает задание напрямую (награда за другое задание, событие, команда оператора). */
    public Active start(String p, QuestDefs.Quest q, Giver g, Map<String, String> vars, int day) {
        Active a = new Active();
        a.id = nextId++; a.qid = q.id(); a.giver = g.npc(); a.giverName = g.name(); a.realm = g.realm(); a.accepted = day; a.level = hooks.level(p);
        a.vars.putAll(vars);
        if (q.deadline() > 0) a.deadline = day + q.deadline();
        a.step = q.steps().get(0).id();
        activeOf(p).add(a);
        enter(p, a, q, day);
        return a;
    }

    // ---------- ход шагов ----------

    private QuestDefs.Step cur(Active a) { QuestDefs.Quest q = defs.quests.get(a.qid); return q == null ? null : q.step(a.step); }

    private void enter(String p, Active a, QuestDefs.Quest q, int day) {
        a.progress = 0;
        QuestDefs.Step s = q.step(a.step);
        if (s != null && s.type().equals("wait")) a.waitUntil = day + s.num("days", 1);
        advanceAuto(p, a, q, day);
    }

    /** Автоматические шаги: уровень, флаг, вступление в орден, ожидание. */
    private void advanceAuto(String p, Active a, QuestDefs.Quest q, int day) {
        for (int guard = 0; guard < 20; guard++) {
            QuestDefs.Step s = q.step(a.step);
            if (s == null) return;
            boolean pass = switch (s.type()) {
                case "level" -> hooks.level(p) >= s.num("at_least", 1);
                case "flag" -> flags(p).contains(sub(s.str("name", ""), a.vars));
                case "join_org" -> hooks.orgRank(p, sub(s.str("org", ""), a.vars)) >= 0;
                case "wait" -> day >= a.waitUntil;
                default -> false;
            };
            if (!pass) return;
            if (!next(p, a, q, s, day)) return;
        }
    }

    /** Переход к следующему шагу; false - задание завершено (выдана награда). */
    private boolean next(String p, Active a, QuestDefs.Quest q, QuestDefs.Step s, int day) {
        a.log.add(sub(s.text(), a.vars));
        String nx = s.next();
        if (nx.isEmpty()) {
            int i = q.steps().indexOf(s);
            if (s.end().isEmpty() && i + 1 < q.steps().size() && !s.type().equals("choice")) nx = q.steps().get(i + 1).id();
        }
        if (nx.isEmpty()) { finish(p, a, q, s.end().isEmpty() ? "success" : s.end(), day); return false; }
        if (q.step(nx) == null) { finish(p, a, q, nx, day); return false; }
        a.step = nx;
        enter(p, a, q, day);
        return activeOf(p).contains(a);
    }

    /** Событие мира для заданий: kill (key = моб), reach (key = государство), action (key = действие), talk выполняется через {@link #interact}. */
    public void report(String p, String type, String key, int n, int day) {
        for (Active a : new ArrayList<>(activeOf(p))) {
            QuestDefs.Quest q = defs.quests.get(a.qid);
            QuestDefs.Step s = q == null ? null : q.step(a.step);
            if (s == null || !s.type().equals(type)) continue;
            String want = sub(s.str(type.equals("kill") ? "mob" : type.equals("reach") ? "realm" : "id", "any"), a.vars);
            if (!(want.equals("any") || want.equals(key) || (type.equals("kill") && want.equals("hostile")))) continue;
            a.progress += n;
            if (a.progress >= (type.equals("kill") ? Math.max(1, parse(sub(s.str("count", "1"), a.vars))) : 1)) next(p, a, q, s, day);
        }
    }

    private static int parse(String s) { return QuestDefs.parse(s, 1); }

    /** Разговор: сдача предметов, шаги talk, завершённые задания. Возвращает реплики для игрока. */
    /** Есть ли у игрока дело к этому собеседнику (доклад или сдача). */
    public boolean canInteract(String p, Giver g) {
        for (Active a : activeOf(p)) {
            QuestDefs.Quest q = defs.quests.get(a.qid);
            QuestDefs.Step s = q == null ? null : q.step(a.step);
            if (s == null || !(s.type().equals("talk") || s.type().equals("fetch"))) continue;
            if (a.giver.equals(g.npc()) || kindOverlap(q, g)) return true;
        }
        return false;
    }

    public List<String> interact(String p, Giver g, int day) {
        List<String> msg = new ArrayList<>();
        for (Active a : new ArrayList<>(activeOf(p))) {
            QuestDefs.Quest q = defs.quests.get(a.qid);
            if (q == null) continue;
            for (int guard = 0; guard < 10; guard++) {
                QuestDefs.Step s = q.step(a.step);
                if (s == null || !activeOf(p).contains(a)) break;
                boolean mine = a.giver.equals(g.npc()) || kindOverlap(q, g);
                if (s.type().equals("fetch")) {
                    if (!mine) break;
                    String item = sub(s.str("item", ""), a.vars);
                    int n = parse(sub(s.str("count", "1"), a.vars));
                    if (hooks.count(p, item) < n) { msg.add("Нужно принести: " + item + " x" + n + " (есть " + hooks.count(p, item) + ")."); break; }
                    if (!hooks.take(p, item, n)) break;
                    msg.add(sub(s.str("reply", "Спасибо, это то, что нужно."), a.vars));
                    if (!next(p, a, q, s, day)) break;
                } else if (s.type().equals("talk")) {
                    String role = sub(s.str("role", ""), a.vars), org = sub(s.str("org", ""), a.vars), npc = sub(s.str("npc", ""), a.vars);
                    boolean who = (!role.isEmpty() && g.roles().contains(role)) || (!org.isEmpty() && g.org().equals(org)) || (!npc.isEmpty() && g.npc().equals(npc)) || (role.isEmpty() && org.isEmpty() && npc.isEmpty() && mine);
                    if (!who) break;
                    String item = sub(s.str("item", ""), a.vars);
                    if (!item.isEmpty()) {
                        int n = parse(sub(s.str("count", "1"), a.vars));
                        if (hooks.count(p, item) < n || !hooks.take(p, item, n)) { msg.add("Нужно передать: " + item + " x" + n + "."); break; }
                    }
                    msg.add(sub(s.str("reply", s.text()), a.vars));
                    if (!next(p, a, q, s, day)) break;
                } else break;
            }
        }
        return msg;
    }

    private boolean kindOverlap(QuestDefs.Quest q, Giver g) { return kindMatch(q, g) && !g.kinds().equals(List.of("any")); }

    /** Выбор в шаге choice. */
    public boolean choose(String p, int activeId, String option, int day) {
        for (Active a : new ArrayList<>(activeOf(p))) {
            if (a.id != activeId) continue;
            QuestDefs.Quest q = defs.quests.get(a.qid);
            QuestDefs.Step s = q == null ? null : q.step(a.step);
            if (s == null || !s.type().equals("choice")) return fail("Сейчас выбирать нечего.");
            for (QuestDefs.Option o : s.options()) {
                if (!o.id().equals(option)) continue;
                if (!ok(p, a.realm, o.requires())) return fail("Этот вариант вам недоступен.");
                int cost = o.requires().get("gold") instanceof Number n ? n.intValue() : 0;
                if (cost > 0 && !hooks.take(p, "gold", cost)) return fail("Не хватает золота: " + cost);
                a.log.add(o.text());
                apply(p, a, o.effects(), day);
                if (o.next().isEmpty()) { next(p, a, q, s, day); return true; }
                if (q.step(o.next()) != null) { a.step = o.next(); enter(p, a, q, day); } else finish(p, a, q, o.next(), day);
                return true;
            }
            return fail("Нет такого варианта.");
        }
        return fail("Нет такого задания.");
    }

    public boolean abandon(String p, int activeId, int day) {
        for (Active a : new ArrayList<>(activeOf(p))) if (a.id == activeId) {
            QuestDefs.Quest q = defs.quests.get(a.qid);
            activeOf(p).remove(a);
            if (q != null && q.outcomes().containsKey("abandon")) apply(p, a, q.outcomes().get("abandon"), day);
            return true;
        }
        return fail("Нет такого задания.");
    }

    // ---------- награды ----------

    private double scale(Active a) { return Math.min(3.0, 1 + 0.06 * (a.level - 1)); }

    @SuppressWarnings("unchecked")
    private void apply(String p, Active a, Map<String, Object> fx, int day) {
        for (var e : fx.entrySet()) {
            Object v = e.getValue();
            switch (e.getKey()) {
                case "gold" -> hooks.give(p, "gold", (int) Math.round(((Number) v).doubleValue() * scale(a)));
                case "xp" -> hooks.xp(p, (int) Math.round(((Number) v).doubleValue() * scale(a)));
                case "items" -> { for (var i : ((Map<String, Object>) v).entrySet()) hooks.give(p, i.getKey(), ((Number) i.getValue()).intValue()); }
                case "rep" -> { for (var i : ((Map<String, Object>) v).entrySet()) hooks.rep(p, sub(i.getKey(), a.vars).replace("$giver", a.realm), ((Number) i.getValue()).intValue()); }
                case "flags" -> { for (Object o : (List<Object>) v) flags(p).add(sub(String.valueOf(o), a.vars)); }
                case "unflags" -> { for (Object o : (List<Object>) v) flags(p).remove(sub(String.valueOf(o), a.vars)); }
                case "rights" -> { for (Object o : (List<Object>) v) { if (rights(p).add(String.valueOf(o))) hooks.notify(p, "Вы получили право: " + o); } }
                case "relation" -> hooks.relation(p, a.giver, ((Number) v).intValue());
                case "news" -> hooks.news(sub(String.valueOf(v), a.vars));
                case "org_influence" -> { for (var i : ((Map<String, Object>) v).entrySet()) hooks.influence(p, sub(i.getKey(), a.vars), ((Number) i.getValue()).intValue()); }
                case "action" -> {
                    Map<String, Object> m = (Map<String, Object>) v;
                    List<String> args = new ArrayList<>();
                    if (m.get("args") instanceof List<?> l) for (Object o : l) args.add(sub(String.valueOf(o), a.vars));
                    hooks.run(p, String.valueOf(m.get("id")), a.realm, args);
                }
                case "follow" -> { QuestDefs.Quest f = defs.quests.get(String.valueOf(v)); if (f != null && activeOf(p).stream().noneMatch(x -> x.qid.equals(f.id()))) start(p, f, new Giver(a.giver, a.giverName, Set.of(), "", "", a.realm), a.vars, day); }
                default -> { }
            }
        }
    }

    private void finish(String p, Active a, QuestDefs.Quest q, String outcome, int day) {
        activeOf(p).remove(a);
        Map<String, Object> fx = q.outcomes().getOrDefault(outcome, Map.of());
        apply(p, a, fx, day);
        if (!outcome.equals("fail") && !outcome.equals("abandon")) {
            done.computeIfAbsent(p, k -> new LinkedHashMap<>()).put(q.id(), day);
            doneCount.merge(p, 1, Integer::sum);
        }
        String line = "[день " + day + "] " + q.name() + (outcome.equals("success") ? "" : " (" + outcome + ")");
        chronicle.add(line);
        while (chronicle.size() > 200) chronicle.remove(0);
        hooks.notify(p, "Задание завершено: " + q.name() + ".");
    }

    // ---------- время ----------

    public void tick(int day) {
        for (var en : new ArrayList<>(active.entrySet())) {
            for (Active a : new ArrayList<>(en.getValue())) {
                QuestDefs.Quest q = defs.quests.get(a.qid);
                if (q == null) { en.getValue().remove(a); continue; }
                if (a.deadline > 0 && day > a.deadline) { en.getValue().remove(a); apply(en.getKey(), a, q.outcomes().getOrDefault("fail", Map.of()), day); hooks.notify(en.getKey(), "Срок вышел: " + q.name() + "."); chronicle.add("[день " + day + "] провалено: " + q.name()); continue; }
                advanceAuto(en.getKey(), a, q, day);
            }
        }
        for (Fired f : fired) if (!f.resolved && !f.owner.isEmpty() && day >= f.expires) resolveDefault(f, day);
    }

    // ---------- события мира ----------

    private QuestDefs.EventChoice choice(QuestDefs.Event e, String id) { for (QuestDefs.EventChoice c : e.choices()) if (c.id().equals(id)) return c; return null; }

    /** Раз в день: в каждое государство с шансом приходит событие. ИИ решает сам, у правителя-игрока событие ждёт выбора. */
    public List<Fired> rollEvents(int day, Random rng, Collection<String> realms, double chance) {
        List<Fired> out = new ArrayList<>();
        for (String realm : realms) {
            if (rng.nextDouble() >= chance) continue;
            List<QuestDefs.Event> pool = new ArrayList<>();
            for (QuestDefs.Event e : defs.events.values()) {
                if (!e.scope().equals("realm")) continue;
                if (day - eventLast.getOrDefault(e.id() + "@" + realm, -9999) < e.cooldown()) continue;
                Map<String, Object> req = e.requires();
                if (req.get("war") instanceof Boolean w && w != hooks.atWar(realm)) continue;
                pool.add(e);
            }
            if (pool.isEmpty()) continue;
            int total = 0;
            for (QuestDefs.Event e : pool) total += e.weight();
            int r = rng.nextInt(total);
            QuestDefs.Event pick = pool.get(0);
            for (QuestDefs.Event e : pool) { r -= e.weight(); if (r < 0) { pick = e; break; } }
            out.add(fire(pick, realm, day, rng));
        }
        return out;
    }

    public Fired fire(QuestDefs.Event e, String realm, int day, Random rng) {
        Fired f = new Fired();
        f.id = nextFired++; f.event = e.id(); f.realm = realm; f.day = day; f.expires = day + e.expire(); f.name = e.name();
        f.text = e.text().replace("{realm}", realm);
        f.owner = hooks.rulerOf(realm);
        fired.add(f);
        while (fired.size() > MAX_FIRED) fired.remove(0);
        eventLast.put(e.id() + "@" + realm, day);
        if (!e.board().isEmpty()) posted.computeIfAbsent(realm, k -> new LinkedHashSet<>()).add(e.board());
        hooks.news(f.name + ": " + f.text);
        if (f.owner.isEmpty()) {                                       // ИИ: вариант по весам ai
            int total = 0;
            for (QuestDefs.EventChoice c : e.choices()) total += c.ai();
            String pick = e.dflt();
            if (total > 0) { int r = rng.nextInt(total); for (QuestDefs.EventChoice c : e.choices()) { r -= c.ai(); if (r < 0) { pick = c.id(); break; } } }
            applyEvent(f, e, choice(e, pick), day);
        } else hooks.notify(f.owner, "Событие: " + f.name + ". Откройте журнал, чтобы решить.");
        return f;
    }

    private void applyEvent(Fired f, QuestDefs.Event e, QuestDefs.EventChoice c, int day) {
        f.resolved = true;
        if (c == null) return;
        f.choice = c.id();
        Active tmp = new Active();
        tmp.realm = f.realm; tmp.level = f.owner.isEmpty() ? 1 : hooks.level(f.owner);
        for (var en : c.effects().entrySet()) {
            Object v = en.getValue();
            switch (en.getKey()) {
                case "news" -> hooks.news(String.valueOf(v).replace("{realm}", f.realm));
                case "treasury" -> hooks.treasury(f.realm, ((Number) v).doubleValue());
                case "market" -> { if (v instanceof List<?> l) for (Object o : l) if (o instanceof Map<?, ?> mm) hooks.shock(f.realm, String.valueOf(mm.get("good")), ((Number) mm.get("factor")).doubleValue(), ((Number) mm.get("days")).intValue()); }
                case "prop" -> { for (var i : ((Map<?, ?>) v).entrySet()) hooks.realmProp(f.realm, String.valueOf(i.getKey()), ((Number) i.getValue()).doubleValue()); }
                case "quest" -> posted.computeIfAbsent(f.realm, k -> new LinkedHashSet<>()).add(String.valueOf(v));
                default -> { if (!f.owner.isEmpty()) apply(f.owner, tmp, Map.of(en.getKey(), v), day); }
            }
        }
    }

    private void resolveDefault(Fired f, int day) {
        QuestDefs.Event e = defs.events.get(f.event);
        if (e != null) applyEvent(f, e, choice(e, e.dflt()), day); else f.resolved = true;
    }

    /** Выбор правителя-игрока. */
    public boolean resolve(String player, int firedId, String choiceId, int day) {
        for (Fired f : fired) {
            if (f.id != firedId) continue;
            if (f.resolved) return fail("Событие уже решено.");
            if (!f.owner.equals(player)) return fail("Это событие не вашего государства.");
            QuestDefs.Event e = defs.events.get(f.event);
            QuestDefs.EventChoice c = e == null ? null : choice(e, choiceId);
            if (c == null) return fail("Нет такого варианта.");
            if (!ok(player, f.realm, c.requires())) return fail("Условия варианта не выполнены.");
            int cost = c.requires().get("gold") instanceof Number n ? n.intValue() : 0;
            if (cost > 0 && !hooks.take(player, "gold", cost)) return fail("Не хватает золота: " + cost);
            applyEvent(f, e, c, day);
            return true;
        }
        return fail("Нет такого события.");
    }

    public List<Fired> pending(String player) {
        List<Fired> l = new ArrayList<>();
        for (Fired f : fired) if (!f.resolved && f.owner.equals(player)) l.add(f);
        return l;
    }

    /** Задания на доске объявлений государства: постоянные (giver board) и вызванные событиями. */
    public Set<String> board(String realm) { return posted.getOrDefault(realm, Set.of()); }

    public Giver boardGiver(String realm) { return Giver.board(realm); }

    // ---------- пользовательский контент и права ----------

    /** Регистрирует вид "quest" в общем хранилище: админ или игрок с правом create_quest создают свои задания. */
    public void registerContent() {
        ContentApi.registerKind("quest", new ContentApi.Handler() {
            @Override public String template(String id, String name) {
                return "{\n \"id\": \"" + id + "\", \"name\": \"" + name + "\", \"tier\": 1, \"giver\": [\"villager\"], \"desc\": \"Опишите просьбу.\",\n"
                        + " \"steps\": [{\"id\": \"a\", \"type\": \"kill\", \"mob\": \"hostile\", \"count\": 5, \"text\": \"Убить 5 врагов\"}],\n \"reward\": {\"gold\": 5, \"xp\": 20}\n}";
            }
            @Override public String validate(String json) {
                try {
                    QuestDefs t = new QuestDefs();
                    t.load(json);
                    if (t.quests.size() != 1) return "Нужно ровно одно задание.";
                    var errs = t.validate();
                    return errs.isEmpty() ? null : errs.get(0);
                } catch (RuntimeException e) { return e.getMessage(); }
            }
            @Override public void apply(String id, String json) { defs.load(json); }
            @Override public void remove(String id) { defs.quests.remove(id); }
            @Override public boolean exists(String id) { return defs.quests.containsKey(id); }
        });
    }

    /** Права для ContentApi: оператор всё; игрок - если получил право create_<вид> (наградой за задание или грамотой), не больше limit записей. */
    public ContentApi.Permission permission(int limit) {
        return (pl, op, kind, action, id, owned) -> {
            if (op) return null;
            if (!hasRight(pl, "create_" + kind)) return "Нужно право create_" + kind + " (выполните задания или получите грамоту государства).";
            if (action.equals("create") && owned >= limit) return "Предел своих записей: " + limit;
            return null;
        };
    }

    // ---------- окно ----------

    @SuppressWarnings("unchecked")
    public String viewJson(String p, int day) {
        Map<String, Object> root = new LinkedHashMap<>();
        List<Object> act = new ArrayList<>();
        for (Active a : activeOf(p)) {
            QuestDefs.Quest q = defs.quests.get(a.qid);
            if (q == null) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", a.id); m.put("name", q.name()); m.put("tier", q.tier()); m.put("giver", a.giverName); m.put("realm", a.realm); m.put("desc", sub(q.desc(), a.vars));
            m.put("deadline", a.deadline > 0 ? a.deadline - day : -1);
            QuestDefs.Step s = q.step(a.step);
            m.put("now", s == null ? "" : sub(s.text(), a.vars));
            if (s != null && s.type().equals("kill")) m.put("progress", a.progress + "/" + sub(s.str("count", "1"), a.vars));
            if (s != null && s.type().equals("fetch")) m.put("progress", hooks.count(p, sub(s.str("item", ""), a.vars)) + "/" + sub(s.str("count", "1"), a.vars));
            m.put("log", new ArrayList<Object>(a.log));
            List<Object> opts = new ArrayList<>();
            if (s != null && s.type().equals("choice")) for (QuestDefs.Option o : s.options()) { Map<String, Object> om = new LinkedHashMap<>(); om.put("id", o.id()); om.put("text", o.text()); om.put("ok", ok(p, a.realm, o.requires())); opts.add(om); }
            m.put("options", opts);
            Map<String, Object> rw = q.outcomes().getOrDefault("success", Map.of());
            m.put("reward", (rw.get("gold") instanceof Number g ? Math.round(g.doubleValue() * scale(a)) + " зол. " : "") + (rw.get("xp") instanceof Number x ? Math.round(x.doubleValue() * scale(a)) + " оп." : ""));
            act.add(m);
        }
        root.put("active", act);
        List<Object> ev = new ArrayList<>();
        for (Fired f : pending(p)) {
            QuestDefs.Event e = defs.events.get(f.event);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", f.id); m.put("name", f.name); m.put("text", f.text); m.put("left", f.expires - day);
            List<Object> opts = new ArrayList<>();
            if (e != null) for (QuestDefs.EventChoice c : e.choices()) { Map<String, Object> om = new LinkedHashMap<>(); om.put("id", c.id()); om.put("text", c.text()); om.put("ok", ok(p, f.realm, c.requires())); opts.add(om); }
            m.put("options", opts);
            ev.add(m);
        }
        root.put("events", ev);
        List<Object> hist = new ArrayList<>();
        for (int i = Math.max(0, chronicle.size() - 25); i < chronicle.size(); i++) hist.add(chronicle.get(i));
        root.put("history", hist);
        root.put("rights", new ArrayList<Object>(rights(p)));
        root.put("done", doneCount(p));
        root.put("day", day);
        return Json.write(root);
    }

    // ---------- сохранение ----------

    public String toJson() {
        Map<String, Object> root = new LinkedHashMap<>();
        Map<String, Object> pl = new LinkedHashMap<>();
        Set<String> players = new LinkedHashSet<>(active.keySet());
        players.addAll(done.keySet()); players.addAll(flags.keySet()); players.addAll(rights.keySet());
        for (String p : players) {
            Map<String, Object> m = new LinkedHashMap<>();
            List<Object> al = new ArrayList<>();
            for (Active a : activeOf(p)) {
                Map<String, Object> am = new LinkedHashMap<>();
                am.put("id", a.id); am.put("qid", a.qid); am.put("giver", a.giver); am.put("gname", a.giverName); am.put("realm", a.realm); am.put("step", a.step);
                am.put("acc", a.accepted); am.put("dl", a.deadline); am.put("wait", a.waitUntil); am.put("prog", a.progress); am.put("lv", a.level);
                am.put("vars", new LinkedHashMap<String, Object>(a.vars)); am.put("log", new ArrayList<Object>(a.log));
                al.add(am);
            }
            m.put("active", al);
            m.put("done", new LinkedHashMap<String, Object>(done.getOrDefault(p, Map.of())));
            m.put("count", doneCount(p));
            m.put("flags", new ArrayList<Object>(flags(p)));
            m.put("rights", new ArrayList<Object>(rights(p)));
            pl.put(p, m);
        }
        root.put("players", pl);
        List<Object> fl = new ArrayList<>();
        for (Fired f : fired) { Map<String, Object> m = new LinkedHashMap<>(); m.put("id", f.id); m.put("day", f.day); m.put("exp", f.expires); m.put("event", f.event); m.put("realm", f.realm); m.put("owner", f.owner); m.put("text", f.text); m.put("name", f.name); m.put("res", f.resolved); m.put("choice", f.choice); fl.add(m); }
        root.put("fired", fl);
        Map<String, Object> pm = new LinkedHashMap<>();
        for (var e : posted.entrySet()) pm.put(e.getKey(), new ArrayList<Object>(e.getValue()));
        root.put("posted", pm);
        root.put("eventLast", new LinkedHashMap<String, Object>(eventLast));
        root.put("chronicle", new ArrayList<Object>(chronicle));
        root.put("nextId", nextId); root.put("nextFired", nextFired);
        return Json.write(root);
    }

    @SuppressWarnings("unchecked")
    public void fromJson(String json) {
        Map<String, Object> root = (Map<String, Object>) Json.parse(json);
        active.clear(); done.clear(); doneCount.clear(); flags.clear(); rights.clear(); posted.clear(); eventLast.clear(); fired.clear(); chronicle.clear();
        for (var e : ((Map<String, Object>) root.getOrDefault("players", Map.of())).entrySet()) {
            String p = e.getKey();
            Map<String, Object> m = (Map<String, Object>) e.getValue();
            for (Object o : (List<Object>) m.get("active")) {
                Map<String, Object> am = (Map<String, Object>) o;
                Active a = new Active();
                a.id = ((Number) am.get("id")).intValue(); a.qid = (String) am.get("qid"); a.giver = (String) am.get("giver"); a.giverName = (String) am.get("gname"); a.realm = (String) am.get("realm");
                a.step = (String) am.get("step"); a.accepted = ((Number) am.get("acc")).intValue(); a.deadline = ((Number) am.get("dl")).intValue(); a.waitUntil = ((Number) am.get("wait")).intValue();
                a.progress = ((Number) am.get("prog")).intValue(); a.level = ((Number) am.get("lv")).intValue();
                for (var v : ((Map<String, Object>) am.get("vars")).entrySet()) a.vars.put(v.getKey(), String.valueOf(v.getValue()));
                for (Object l : (List<Object>) am.get("log")) a.log.add(String.valueOf(l));
                activeOf(p).add(a);
            }
            Map<String, Integer> d = done.computeIfAbsent(p, k -> new LinkedHashMap<>());
            for (var v : ((Map<String, Object>) m.get("done")).entrySet()) d.put(v.getKey(), ((Number) v.getValue()).intValue());
            doneCount.put(p, ((Number) m.get("count")).intValue());
            for (Object o : (List<Object>) m.get("flags")) flags(p).add(String.valueOf(o));
            for (Object o : (List<Object>) m.get("rights")) rights(p).add(String.valueOf(o));
        }
        for (Object o : (List<Object>) root.getOrDefault("fired", List.of())) {
            Map<String, Object> m = (Map<String, Object>) o;
            Fired f = new Fired();
            f.id = ((Number) m.get("id")).intValue(); f.day = ((Number) m.get("day")).intValue(); f.expires = ((Number) m.get("exp")).intValue(); f.event = (String) m.get("event"); f.realm = (String) m.get("realm");
            f.owner = (String) m.get("owner"); f.text = (String) m.get("text"); f.name = (String) m.get("name"); f.resolved = Boolean.TRUE.equals(m.get("res")); f.choice = String.valueOf(m.get("choice"));
            fired.add(f);
        }
        for (var e : ((Map<String, Object>) root.getOrDefault("posted", Map.of())).entrySet()) for (Object o : (List<Object>) e.getValue()) posted.computeIfAbsent(e.getKey(), k -> new LinkedHashSet<>()).add(String.valueOf(o));
        for (var e : ((Map<String, Object>) root.getOrDefault("eventLast", Map.of())).entrySet()) eventLast.put(e.getKey(), ((Number) e.getValue()).intValue());
        for (Object o : (List<Object>) root.getOrDefault("chronicle", List.of())) chronicle.add(String.valueOf(o));
        nextId = ((Number) root.getOrDefault("nextId", 1)).intValue(); nextFired = ((Number) root.getOrDefault("nextFired", 1)).intValue();
    }
}
