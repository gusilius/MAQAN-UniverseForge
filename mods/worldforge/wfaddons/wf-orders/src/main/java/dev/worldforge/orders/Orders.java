package dev.worldforge.orders;

import dev.worldforge.mod.Json;

import java.util.*;

/**
 * Организации: гильдии, ордена, братства. Игрок вступает за взнос, растёт по званиям через влияние (задания, пожертвования),
 * платит членские взносы, может подавать прошения. У организации своя казна и сила, враждебные организации исключают друг друга.
 * Чистая логика: предметы, золото и действия карты идут через {@link Hooks}.
 */
public final class Orders {
    public interface Hooks {
        int count(String player, String item);
        boolean take(String player, String item, int n);
        void give(String player, String item, int n);
        String realmOf(String player);
        /** Действие карты от имени игрока; null при отказе (причина в lastError). */
        String run(String player, String action, String realm, List<String> args);
    }

    public static class Hooks0 implements Hooks {
        public int count(String p, String i) { return 0; }
        public boolean take(String p, String i, int n) { return false; }
        public void give(String p, String i, int n) {}
        public String realmOf(String p) { return ""; }
        public String run(String p, String a, String r, List<String> args) { return null; }
    }

    public static final class Member {
        public int rank, influence, joined, paidDay, late, done;
        public final Map<String, Integer> petitionDay = new LinkedHashMap<>();
    }

    public static final class Contract {
        public int id;
        public String org, player, mission, type, item, text;
        public int count, progress, reward, influence, expires;
        public boolean accepted, finished;
    }

    public static final class State {
        public double power, treasury;
        public int members;
    }

    public static final int MAX_ORGS = 2, DUES_EVERY = 7, MAX_LATE = 3, OFFERS = 3, REJOIN_DAYS = 20;
    public static final String GOLD = "gold";

    public final Map<String, OrgDef> defs = new LinkedHashMap<>();
    private final Map<String, State> states = new LinkedHashMap<>();
    private final Map<String, Member> members = new LinkedHashMap<>();
    private final Map<Integer, Contract> contracts = new LinkedHashMap<>();
    private final Map<String, Integer> left = new LinkedHashMap<>();
    private int nextContract = 1;
    public Hooks hooks = new Hooks0();
    public String lastError = "";
    public final List<String> chronicle = new ArrayList<>();

    private static String key(String player, String org) { return player + "|" + org; }

    public void add(OrgDef d) {
        defs.put(d.id, d);
        states.computeIfAbsent(d.id, k -> { State s = new State(); s.power = d.startPower; s.treasury = d.startTreasury; s.members = 8 + d.startPower / 4; return s; });
    }

    public void load(String json) { add(OrgDef.parse(json)); }

    public State state(String org) { return states.get(org); }

    public Member member(String player, String org) { return members.get(key(player, org)); }

    public List<String> orgsOf(String player) {
        List<String> out = new ArrayList<>();
        for (String k : members.keySet()) if (k.startsWith(player + "|")) out.add(k.substring(player.length() + 1));
        return out;
    }

    public int rankOf(String player, String org) { Member m = member(player, org); return m == null ? -1 : m.rank; }

    public String rankName(String player, String org) { Member m = member(player, org); return m == null ? "" : defs.get(org).ranks.get(m.rank).name(); }

    /** Сумма бонуса за звания во всех организациях игрока (например discount_pct, tax_relief). Другие аддоны читают это значение. */
    public int perk(String player, String key) {
        int sum = 0;
        for (String o : orgsOf(player)) sum += defs.get(o).ranks.get(member(player, o).rank).perks().getOrDefault(key, 0);
        return sum;
    }

    private void log(String s) { chronicle.add(s); while (chronicle.size() > 100) chronicle.remove(0); }

    // ---------- вступление ----------

    public String canJoin(String player, String org, int day) {
        OrgDef d = defs.get(org);
        if (d == null) return "Нет такой организации.";
        if (member(player, org) != null) return "Вы уже состоите в «" + d.name + "».";
        if (orgsOf(player).size() >= MAX_ORGS) return "Нельзя состоять больше чем в " + MAX_ORGS + " организациях.";
        for (String o : orgsOf(player)) if (d.rivals.contains(o) || defs.get(o).rivals.contains(org)) return "Вы состоите в «" + defs.get(o).name + "», а они враждуют.";
        Integer l = left.get(key(player, org));
        if (l != null && day - l < REJOIN_DAYS) return "Вы недавно ушли отсюда. Вернуться можно через " + (REJOIN_DAYS - (day - l)) + " дн.";
        if (hooks.count(player, GOLD) < d.joinGold) return "Вступительный взнос: " + d.joinGold + " золота.";
        return null;
    }

    public boolean join(String player, String org, int day) {
        String why = canJoin(player, org, day);
        if (why != null) { lastError = why; return false; }
        OrgDef d = defs.get(org);
        if (!hooks.take(player, GOLD, d.joinGold)) { lastError = "Не удалось внести взнос."; return false; }
        State s = states.get(org);
        s.treasury += d.joinGold; s.members++; s.power += 0.5;
        Member m = new Member();
        m.joined = day; m.paidDay = day;
        members.put(key(player, org), m);
        log(player + " вступил в «" + d.name + "»");
        return true;
    }

    public void leave(String player, String org, int day) {
        if (members.remove(key(player, org)) == null) return;
        State s = states.get(org);
        s.members = Math.max(1, s.members - 1);
        left.put(key(player, org), day);
        contracts.values().removeIf(c -> c.player.equals(player) && c.org.equals(org) && !c.finished);
        log(player + " покинул «" + defs.get(org).name + "»");
    }

    // ---------- влияние и звания ----------

    /** Добавляет влияние и повышает звание, если порог пройден. Возвращает новое звание или null. */
    public String addInfluence(String player, String org, int n) {
        Member m = member(player, org);
        if (m == null) return null;
        OrgDef d = defs.get(org);
        m.influence = Math.max(0, m.influence + n);
        String promoted = null;
        while (m.rank + 1 < d.ranks.size() && m.influence >= d.ranks.get(m.rank + 1).influence()) {
            m.rank++;
            promoted = d.ranks.get(m.rank).name();
            log(player + " получил звание «" + promoted + "» в «" + d.name + "»");
        }
        while (m.rank > 0 && m.influence < d.ranks.get(m.rank).influence()) { m.rank--; }
        return promoted;
    }

    public boolean donate(String player, String org, int gold) {
        Member m = member(player, org);
        if (m == null) { lastError = "Вы не состоите в этой организации."; return false; }
        if (gold <= 0 || !hooks.take(player, GOLD, gold)) { lastError = "Не хватает золота."; return false; }
        State s = states.get(org);
        s.treasury += gold; s.power += gold / 100.0;
        addInfluence(player, org, Math.max(1, gold / 5));
        return true;
    }

    // ---------- контракты ----------

    public List<Contract> contractsOf(String player, String org) {
        List<Contract> out = new ArrayList<>();
        for (Contract c : contracts.values()) if (c.player.equals(player) && c.org.equals(org) && !c.finished) out.add(c);
        return out;
    }

    public Contract contract(int id) { return contracts.get(id); }

    /** Обновляет предложения: просроченные убираются, добирается до OFFERS по заданиям, доступным по званию. */
    public List<Contract> offers(String player, String org, int day, Random rng) {
        Member m = member(player, org);
        if (m == null) return List.of();
        contracts.values().removeIf(c -> c.player.equals(player) && c.org.equals(org) && !c.finished && c.expires < day);
        List<Contract> cur = contractsOf(player, org);
        OrgDef d = defs.get(org);
        List<OrgDef.Mission> pool = new ArrayList<>();
        for (OrgDef.Mission ms : d.missions) if (ms.minRank() <= m.rank) pool.add(ms);
        int guard = 0;
        while (cur.size() < OFFERS && !pool.isEmpty() && guard++ < 20) {
            OrgDef.Mission ms = pool.get(rng.nextInt(pool.size()));
            boolean dup = false;
            for (Contract c : cur) if (c.mission.equals(ms.id())) dup = true;
            if (dup && pool.size() > cur.size()) continue;
            Contract c = new Contract();
            c.id = nextContract++;
            c.org = org; c.player = player; c.mission = ms.id(); c.type = ms.type(); c.item = ms.item();
            c.count = ms.min() + (ms.max() > ms.min() ? rng.nextInt(ms.max() - ms.min() + 1) : 0);
            int scale = Math.max(1, ms.min());
            c.reward = ms.reward() * c.count / scale;
            c.influence = Math.max(1, ms.influence() * c.count / scale);
            c.expires = day + ms.days();
            c.text = ms.text().replace("{n}", String.valueOf(c.count)).replace("{item}", c.item);
            contracts.put(c.id, c);
            cur.add(c);
        }
        return cur;
    }

    public boolean accept(String player, int id) {
        Contract c = contracts.get(id);
        if (c == null || !c.player.equals(player) || c.finished) { lastError = "Нет такого задания."; return false; }
        c.accepted = true;
        return true;
    }

    /** Сдать задание: fetch/gold забирают предметы или золото, deed требует набранного прогресса. */
    public boolean complete(String player, int id, int day) {
        Contract c = contracts.get(id);
        if (c == null || !c.player.equals(player) || c.finished) { lastError = "Нет такого задания."; return false; }
        if (!c.accepted) { lastError = "Сначала возьмите задание."; return false; }
        if (c.expires < day) { c.finished = true; addInfluence(player, c.org, -2); lastError = "Срок вышел."; return false; }
        switch (c.type) {
            case "fetch" -> { if (hooks.count(player, c.item) < c.count || !hooks.take(player, c.item, c.count)) { lastError = "Нужно: " + c.count + " × " + c.item + "."; return false; } }
            case "gold" -> { if (!hooks.take(player, GOLD, c.count)) { lastError = "Нужно " + c.count + " золота."; return false; } states.get(c.org).treasury += c.count; }
            default -> { if (c.progress < c.count) { lastError = "Выполнено " + c.progress + " из " + c.count + "."; return false; } }
        }
        c.finished = true;
        if (c.reward > 0) hooks.give(player, GOLD, c.reward);
        Member m = member(player, c.org);
        if (m != null) m.done++;
        State s = states.get(c.org);
        s.power += 0.3;
        addInfluence(player, c.org, c.influence);
        return true;
    }

    /** События мира (убит враг, добыта руда...): продвигает задания-деяния с этим предметом. */
    public void report(String player, String deed, int n) {
        for (Contract c : contracts.values()) if (c.player.equals(player) && c.accepted && !c.finished && c.type.equals("deed") && c.item.equals(deed)) c.progress = Math.min(c.count, c.progress + n);
    }

    // ---------- прошения ----------

    public String canPetition(String player, String org, String pid, int day) {
        Member m = member(player, org);
        if (m == null) return "Вы не состоите в этой организации.";
        OrgDef.Petition p = petition(org, pid);
        if (p == null) return "Нет такого прошения.";
        if (m.rank < p.minRank()) return "Нужно звание «" + defs.get(org).ranks.get(p.minRank()).name() + "».";
        if (m.influence < p.cost()) return "Нужно влияния: " + p.cost() + ".";
        Integer last = m.petitionDay.get(pid);
        if (last != null && day - last < p.cooldown()) return "Рано: повторить через " + (p.cooldown() - (day - last)) + " дн.";
        if (p.kind().equals("gold") && states.get(org).treasury < p.amount()) return "В казне организации пусто.";
        return null;
    }

    public OrgDef.Petition petition(String org, String id) {
        OrgDef d = defs.get(org);
        if (d != null) for (OrgDef.Petition p : d.petitions) if (p.id().equals(id)) return p;
        return null;
    }

    public String petition(String player, String org, String pid, int day) {
        String why = canPetition(player, org, pid, day);
        if (why != null) { lastError = why; return null; }
        OrgDef.Petition p = petition(org, pid);
        Member m = member(player, org);
        String msg;
        if (p.kind().equals("gold")) {
            states.get(org).treasury -= p.amount();
            hooks.give(player, GOLD, p.amount());
            msg = "Из казны выдано " + p.amount() + " золота.";
        } else {
            String realm = hooks.realmOf(player);
            if (realm.isEmpty()) { lastError = "Вы не правите государством."; return null; }
            List<String> args = new ArrayList<>();
            if (!p.args().isEmpty()) for (String a : p.args().split("\\|")) args.add(a.replace("{realm_id}", realm).trim());
            msg = hooks.run(player, p.action(), realm, args);
            if (msg == null) return null;
        }
        m.influence -= p.cost();
        while (m.rank > 0 && m.influence < defs.get(org).ranks.get(m.rank).influence()) m.rank--;
        m.petitionDay.put(pid, day);
        return msg.isEmpty() ? "Готово." : msg;
    }

    // ---------- время ----------

    /** Раз в день. Взносы раз в неделю, рост казны и силы, раз в год - соперничество. */
    public void tick(int day, Random rng) {
        for (Map.Entry<String, Member> e : new ArrayList<>(members.entrySet())) {
            Member m = e.getValue();
            String[] k = e.getKey().split("\\|", 2);
            OrgDef d = defs.get(k[1]);
            if (d == null || day - m.paidDay < DUES_EVERY) continue;
            int due = d.dues * (1 + m.rank);
            if (due == 0 || hooks.take(k[0], GOLD, due)) { states.get(k[1]).treasury += due; m.late = 0; }
            else if (++m.late >= MAX_LATE) { addInfluence(k[0], k[1], -5); leave(k[0], k[1], day); log(k[0] + " исключён из «" + d.name + "» за долги"); continue; }
            m.paidDay = day;
        }
        for (Map.Entry<String, State> e : states.entrySet()) {
            State s = e.getValue();
            s.treasury += s.power * 0.15 + s.members * 0.05;
            s.power = Math.max(1, Math.min(100, s.power + (s.members > 14 + s.power / 3 ? 0.02 : -0.01)));
            s.members = Math.max(2, s.members + (rng.nextInt(40) == 0 ? 1 : 0) - (rng.nextInt(60) == 0 ? 1 : 0));
        }
        if (day > 0 && day % 365 == 0) rivalry(rng);
    }

    /** Соперники ежегодно отбирают друг у друга силу и участников. */
    public void rivalry(Random rng) {
        for (OrgDef a : defs.values()) for (String r : a.rivals) {
            OrgDef b = defs.get(r);
            if (b == null || a.id.compareTo(b.id) > 0) continue;
            State sa = states.get(a.id), sb = states.get(b.id);
            boolean aWins = rng.nextDouble() < sa.power / Math.max(1, sa.power + sb.power);
            State w = aWins ? sa : sb, l = aWins ? sb : sa;
            double take = Math.min(l.power - 1, 3 + rng.nextInt(4));
            if (take > 0) { l.power -= take; w.power = Math.min(100, w.power + take / 2); }
            log("Соперничество: «" + (aWins ? a : b).name + "» одолела «" + (aWins ? b : a).name + "»");
        }
    }

    // ---------- вид для окна ----------

    public String viewJson(String player, int day, String realm) {
        List<Object> orgs = new ArrayList<>();
        for (OrgDef d : defs.values()) {
            Map<String, Object> o = new LinkedHashMap<>();
            State s = states.get(d.id);
            o.put("id", d.id); o.put("name", d.name); o.put("kind", d.kind); o.put("desc", d.desc); o.put("color", d.color); o.put("seat", d.seat);
            o.put("power", Math.round(s.power * 10) / 10.0); o.put("treasury", (int) s.treasury); o.put("members", s.members);
            o.put("joinGold", d.joinGold); o.put("dues", d.dues); o.put("rivals", new ArrayList<Object>(d.rivals));
            o.put("liked", new ArrayList<Object>(d.liked)); o.put("disliked", new ArrayList<Object>(d.disliked));
            List<Object> rs = new ArrayList<>();
            for (OrgDef.Rank r : d.ranks) rs.add(new LinkedHashMap<String, Object>(Map.of("name", r.name(), "influence", r.influence(), "perks", new LinkedHashMap<String, Object>(r.perks()))));
            o.put("ranks", rs);
            Member m = member(player, d.id);
            o.put("joined", m != null);
            String why = m == null ? canJoin(player, d.id, day) : null;
            o.put("canJoin", m == null && why == null); o.put("why", why == null ? "" : why);
            if (m != null) {
                o.put("rank", m.rank); o.put("influence", m.influence); o.put("done", m.done); o.put("late", m.late);
                o.put("since", day - m.joined);
                List<Object> cs = new ArrayList<>();
                for (Contract c : contractsOf(player, d.id)) {
                    Map<String, Object> cm = new LinkedHashMap<>();
                    cm.put("id", c.id); cm.put("text", c.text); cm.put("type", c.type); cm.put("item", c.item); cm.put("n", c.count); cm.put("progress", c.progress);
                    cm.put("reward", c.reward); cm.put("inf", c.influence); cm.put("left", c.expires - day); cm.put("accepted", c.accepted);
                    cs.add(cm);
                }
                o.put("contracts", cs);
                List<Object> ps = new ArrayList<>();
                for (OrgDef.Petition p : d.petitions) {
                    Map<String, Object> pm = new LinkedHashMap<>();
                    String no = canPetition(player, d.id, p.id(), day);
                    pm.put("id", p.id()); pm.put("name", p.name()); pm.put("desc", p.desc()); pm.put("cost", p.cost()); pm.put("minRank", p.minRank());
                    pm.put("ok", no == null); pm.put("why", no == null ? "" : no);
                    ps.add(pm);
                }
                o.put("petitions", ps);
            }
            orgs.add(o);
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("orgs", orgs); root.put("day", day); root.put("realm", realm);
        root.put("chronicle", new ArrayList<Object>(chronicle.subList(Math.max(0, chronicle.size() - 12), chronicle.size())));
        return Json.write(root);
    }

    // ---------- сохранение ----------

    public String toJson() {
        Map<String, Object> root = new LinkedHashMap<>();
        Map<String, Object> st = new LinkedHashMap<>();
        for (var e : states.entrySet()) st.put(e.getKey(), new LinkedHashMap<String, Object>(Map.of("p", e.getValue().power, "t", e.getValue().treasury, "m", e.getValue().members)));
        root.put("states", st);
        Map<String, Object> ms = new LinkedHashMap<>();
        for (var e : members.entrySet()) {
            Member m = e.getValue();
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("r", m.rank); o.put("i", m.influence); o.put("j", m.joined); o.put("p", m.paidDay); o.put("l", m.late); o.put("d", m.done);
            o.put("pet", new LinkedHashMap<String, Object>(m.petitionDay));
            ms.put(e.getKey(), o);
        }
        root.put("members", ms);
        List<Object> cs = new ArrayList<>();
        for (Contract c : contracts.values()) {
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("id", c.id); o.put("org", c.org); o.put("pl", c.player); o.put("ms", c.mission); o.put("type", c.type); o.put("item", c.item); o.put("text", c.text);
            o.put("n", c.count); o.put("pr", c.progress); o.put("rw", c.reward); o.put("inf", c.influence); o.put("exp", c.expires); o.put("acc", c.accepted); o.put("fin", c.finished);
            cs.add(o);
        }
        root.put("contracts", cs); root.put("next", nextContract); root.put("left", new LinkedHashMap<String, Object>(left));
        root.put("chronicle", new ArrayList<Object>(chronicle));
        return Json.write(root);
    }

    private static int num(Object o) { return o instanceof Number n ? n.intValue() : 0; }
    private static double dbl(Object o) { return o instanceof Number n ? n.doubleValue() : 0; }
    private static String s(Object o) { return o == null ? "" : String.valueOf(o); }

    public void fromJson(String json) {
        members.clear(); contracts.clear(); left.clear(); chronicle.clear(); nextContract = 1;
        if (!(Json.parse(json) instanceof Map<?, ?> root)) return;
        if (root.get("states") instanceof Map<?, ?> st) for (var e : st.entrySet()) {
            State s = states.get(String.valueOf(e.getKey()));
            if (s == null || !(e.getValue() instanceof Map<?, ?> m)) continue;
            s.power = dbl(m.get("p")); s.treasury = dbl(m.get("t")); s.members = num(m.get("m"));
        }
        if (root.get("members") instanceof Map<?, ?> ms) for (var e : ms.entrySet()) {
            if (!(e.getValue() instanceof Map<?, ?> o)) continue;
            Member m = new Member();
            m.rank = num(o.get("r")); m.influence = num(o.get("i")); m.joined = num(o.get("j")); m.paidDay = num(o.get("p")); m.late = num(o.get("l")); m.done = num(o.get("d"));
            if (o.get("pet") instanceof Map<?, ?> pm) for (var x : pm.entrySet()) m.petitionDay.put(String.valueOf(x.getKey()), num(x.getValue()));
            members.put(String.valueOf(e.getKey()), m);
        }
        if (root.get("contracts") instanceof List<?> cs) for (Object x : cs) {
            if (!(x instanceof Map<?, ?> o)) continue;
            Contract c = new Contract();
            c.id = num(o.get("id")); c.org = s(o.get("org")); c.player = s(o.get("pl")); c.mission = s(o.get("ms")); c.type = s(o.get("type")); c.item = s(o.get("item")); c.text = s(o.get("text"));
            c.count = num(o.get("n")); c.progress = num(o.get("pr")); c.reward = num(o.get("rw")); c.influence = num(o.get("inf")); c.expires = num(o.get("exp"));
            c.accepted = Boolean.TRUE.equals(o.get("acc")); c.finished = Boolean.TRUE.equals(o.get("fin"));
            contracts.put(c.id, c);
        }
        nextContract = Math.max(1, num(root.get("next")));
        if (root.get("left") instanceof Map<?, ?> lm) for (var e : lm.entrySet()) left.put(String.valueOf(e.getKey()), num(e.getValue()));
        if (root.get("chronicle") instanceof List<?> l) for (Object o : l) chronicle.add(String.valueOf(o));
    }
}
