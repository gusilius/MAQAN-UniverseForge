package dev.worldforge.court;

import dev.worldforge.people.Character;
import dev.worldforge.people.CharacterRegistry;
import dev.worldforge.people.CharacterStats;
import dev.worldforge.people.Courts;
import dev.worldforge.people.NameBank;
import dev.worldforge.people.Role;
import dev.worldforge.people.RoleDefs;
import dev.worldforge.people.Succession;

import dev.worldforge.mod.Json;
import dev.worldforge.mod.TitleData;
import dev.worldforge.mod.TitleEditor;

import java.util.*;

/**
 * Жизнь двора поверх {@link CharacterRegistry}: рождения и смерти с наследованием, мнения персонажей, совет, фракции вассалов,
 * козни и события (CK3 / Stellaris). Ход - {@link #tick(double)} в днях; игроки отвечают на события и плетут козни сами,
 * для остальных государств ответы выбирает ИИ. Связь с другими аддонами - через {@link Hooks} и свойства государств
 * ("council.income_pct" и т.д., см. {@link #syncProps}).
 */
public final class CourtSim {
    public interface Hooks {
        default void gold(String realm, double delta) { }

        default boolean atWar(String realm) { return false; }

        /** Фракция выдвинула ультиматум: члены хотят независимости. */
        default void independence(String realm, String liege) { }

        default void news(String text) { }

        /** Заключён династический брак между государствами. */
        default void marriage(String realmA, String realmB) { }

        /** Герой игрока умер, управление перешло к наследнику. */
        default void playerHeir(String player, String oldId, String newId) { }

        /** Герой игрока умер без наследника: нужен новый персонаж. */
        default void playerDied(String player) { }
    }

    public static final class Pending {
        public final int id;
        public final String realm, character, event;
        public final long day;

        Pending(int id, String realm, String character, String event, long day) { this.id = id; this.realm = realm; this.character = character; this.event = event; this.day = day; }
    }

    public final TitleData d;
    public final TitleEditor ed;
    public final CharacterRegistry reg;
    public final RoleDefs defs;
    public final NameBank names;
    public final Opinions opinions = new Opinions();
    public final Map<Integer, Faction> factions = new LinkedHashMap<>();
    public final Map<Integer, Scheme> schemes = new LinkedHashMap<>();
    public final Map<String, EventDef> events = new LinkedHashMap<>();
    public final List<Pending> pending = new ArrayList<>();
    public final List<String> news = new ArrayList<>();
    public final Map<String, String> players = new LinkedHashMap<>();      // игрок -> его персонаж
    public String me = "";                                                  // персонаж того, кому показывают срез (только на клиенте)
    public final Set<String> humans = new LinkedHashSet<>();               // государства под управлением игроков
    public final Map<String, Integer> cooldown = new LinkedHashMap<>();    // "персонаж|событие" -> год, когда можно снова
    public Hooks hooks = new Hooks() { };
    public final long seed;
    public long day;
    public final int startYear;
    private int nextId = 1;

    public CourtSim(TitleData d, TitleEditor ed, CharacterRegistry reg, RoleDefs defs, NameBank names, long seed, int startYear) {
        this.d = d; this.ed = ed; this.reg = reg; this.defs = defs; this.names = names; this.seed = seed; this.startYear = startYear;
    }

    public int year() { return startYear + (int) (day / 365); }

    private final List<String> eventSrc = new ArrayList<>();

    public void addEvent(String json) { EventDef e = EventDef.parse(json); events.put(e.id, e); eventSrc.add(json); }

    /** Исходные JSON событий (для отправки клиенту). */
    public List<String> eventSources() { return eventSrc; }

    public void announce(String s) { say(s); }

    private void say(String s) {
        String line = "[" + year() + "] " + s;
        news.add(line);
        while (news.size() > 150) news.remove(0);
        hooks.news(line);
    }

    // ------------------------------------------------------------------ справка

    public String realmOf(Character c) {
        for (Character.Post p : c.posts) if (p.place.startsWith("realm:")) return p.place.substring(6);
        return "";
    }

    public Character ruler(String realm) {
        Role r = defs.ruler();
        return r == null || realm == null || realm.isEmpty() ? null : reg.holder(r.id, Courts.realmPlace(realm));
    }

    // ------------------------------------------------------------------ игроки

    public Character playing(String player) { String id = players.get(player); return id == null ? null : reg.get(id); }

    /** Игрок берёт персонажа: живого, совершеннолетнего, не занятого другим игроком. Государство, где он правитель, переходит под управление игроков. */
    public Character play(String player, String charId) {
        Character c = reg.get(charId);
        if (c == null || !c.alive) throw new IllegalArgumentException("Нет живого персонажа " + charId);
        if (year() - c.birthYear < 16) throw new IllegalArgumentException(c.name + " ещё слишком молод");
        for (Map.Entry<String, String> e : players.entrySet()) if (e.getValue().equals(charId) && !e.getKey().equals(player)) throw new IllegalArgumentException(c.name + " уже под управлением игрока " + e.getKey());
        release(player);
        players.put(player, charId);
        c.props.put("player", player);
        String realm = realmOf(c);
        if (!realm.isEmpty() && ruler(realm) == c) humans.add(realm);
        say(player + " ведёт " + c.name + (realm.isEmpty() ? "" : " (" + realmName(realm) + ")"));
        return c;
    }

    public void release(String player) {
        String id = players.remove(player);
        Character c = id == null ? null : reg.get(id);
        if (c == null) return;
        c.props.remove("player");
        String realm = realmOf(c);
        if (!realm.isEmpty() && ruler(realm) == c && players.values().stream().noneMatch(x -> realm.equals(realmOf(reg.get(x))))) humans.remove(realm);
    }

    /** Наследник героя игрока: старший живой совершеннолетний ребёнок. */
    private Character heirOf(Character c) {
        Character best = null;
        for (String id : c.children) {
            Character k = reg.get(id);
            if (k != null && k.alive && year() - k.birthYear >= 16 && (best == null || k.birthYear < best.birthYear)) best = k;
        }
        return best;
    }

    private void handOver(String player, Character dead, Character heir) {
        players.remove(player);
        dead.props.remove("player");
        if (heir == null) { say(player + " остался без героя"); hooks.playerDied(player); return; }
        players.put(player, heir.id);
        heir.props.put("player", player);
        String realm = realmOf(heir);
        if (!realm.isEmpty() && ruler(realm) == heir) humans.add(realm);
        say(player + " теперь ведёт " + heir.name);
        hooks.playerHeir(player, dead.id, heir.id);
    }

    public String realmName(String id) { TitleData.Realm r = d.realms.get(id); return r == null ? id : r.name; }

    public int opinion(String fromChar, String toChar) { return opinions.opinion(reg, fromChar, toChar, year()); }

    public Map<String, Double> council(String realm) { return Council.effects(reg, defs, realm); }

    public Random rnd(String salt, long extra) {
        long z = seed * 31 + salt.hashCode() * 17L + extra * 0x9E3779B97F4A7C15L;     // splitmix64: соседние семена не должны давать похожие броски
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return new Random(z ^ (z >>> 31));
    }

    // ------------------------------------------------------------------ ход

    public void tick(double days) {
        long target = day + (long) Math.floor(days);
        while (day < target) {
            day++;
            stepSchemes();
            if (day % 30 == 0) { monthly(); }
            if (day % 365 == 0) yearly();
        }
    }

    private void monthly() {
        opinions.expire(year());
        updateFactions();
        rollEvents();
        syncProps();
    }

    private void yearly() {
        lifeCycle();
        Marriages.aiYear(this);
        if (ed != null) Courts.ensure(d, ed, reg, defs, names, seed + day, year());
    }

    // ------------------------------------------------------------------ жизнь и смерть

    private void lifeCycle() {
        int y = year();
        List<Character> all = new ArrayList<>(reg.all());
        for (Character c : all) {
            if (!c.alive) continue;
            int age = y - c.birthYear;
            double p = age < 50 ? 0.003 : Math.min(0.55, 0.01 + Math.pow(age - 50, 2) * 0.0007);
            if (c.traits.contains("craven") || CharacterStats.traits(c).contains("lazy")) p *= 1.05;
            if (rnd("death", c.id.hashCode() + day).nextDouble() < p) die(c, "умер от старости или болезни");
        }
        for (Character c : all) {
            if (!c.alive || !c.gender.equals("f") || c.spouse.isEmpty()) continue;
            Character f = reg.get(c.spouse);
            int age = y - c.birthYear;
            if (f == null || !f.alive || age < 18 || age > 40 || c.children.size() >= 6) continue;
            if (rnd("birth", c.id.hashCode() + day).nextDouble() < 0.24) bear(f, c);
        }
    }

    private void bear(Character father, Character mother) {
        Random r = rnd("child", father.id.hashCode() + mother.id.hashCode() + day);
        NameBank.Pool pool = names.pool(names.culture(father.culture));
        boolean m = r.nextBoolean();
        List<String> l = m ? pool.male : pool.female;
        if (l.isEmpty()) return;
        Character kid = reg.addChild(father, mother, l.get(r.nextInt(l.size())), m ? "m" : "f", year());
        String realm = realmOf(father);
        if (!realm.isEmpty() && ruler(realm) == father) say("В доме " + realmName(realm) + " родился " + (m ? "наследник " : "ребёнок ") + kid.name);
    }

    /** Смерть персонажа: должности освобождаются, правитель получает наследника. */
    public void die(Character c, String cause) {
        if (!c.alive) return;
        String realm = realmOf(c);
        boolean wasRuler = !realm.isEmpty() && ruler(realm) == c;
        TitleData.Realm politicalRealm = realm.isEmpty() ? null : d.realms.get(realm);
        String law = politicalRealm == null ? "primogeniture" : String.valueOf(
                politicalRealm.props.getOrDefault("politics.succession_law",
                        politicalRealm.props.getOrDefault("succession", "primogeniture")));
        if (wasRuler) {
            List<Succession.Result> res = Succession.onDeath(reg, defs, d, ed, names, seed + day, c.id, year(), law);
            for (Succession.Result s : res) say(c.name + " (" + realmName(realm) + ") " + cause + ". Наследует " + s.heir.name + (s.usurper ? " (узурпатор)" : ""));
        } else {
            reg.kill(c.id);
            if (!realm.isEmpty()) say(c.name + " (" + realmName(realm) + ") " + cause);
        }
        schemes.values().removeIf(s -> s.owner.equals(c.id) || s.target.equals(c.id));
        for (String pl : new ArrayList<>(players.keySet())) {
            if (!players.get(pl).equals(c.id)) continue;
            Character heir = null;
            if (wasRuler) { Character nr = ruler(realm); if (nr != null && nr != c) heir = nr; }
            if (heir == null) heir = heirOf(c);
            handOver(pl, c, heir);
        }
    }

    // ------------------------------------------------------------------ фракции

    private double weight(String realm) { TitleData.Realm r = d.realms.get(realm); return r == null ? 0 : Math.max(1, r.areaBlocks); }

    private Faction factionOf(String liege, String member) {
        for (Faction f : factions.values()) if (f.liege.equals(liege) && f.members.contains(member)) return f;
        return null;
    }

    private void updateFactions() {
        for (TitleData.Realm L : d.realmList()) {
            Character lr = ruler(L.id);
            if (lr == null) continue;
            double bonus = council(L.id).getOrDefault("vassal_opinion", 0.0);
            List<String> subs = new ArrayList<>(L.subjectIds);
            Faction f = null;
            for (Faction x : factions.values()) if (x.liege.equals(L.id)) f = x;
            for (String s : subs) {
                Character sr = ruler(s);
                if (sr == null) continue;
                TitleData.Realm vassalRealm = d.realms.get(s);
                Object rawLoyalty = vassalRealm == null ? null : vassalRealm.props.get("politics.vassal_loyalty");
                int contractualLoyalty = rawLoyalty instanceof Number n ? Math.max(0, Math.min(100, n.intValue())) : 50;
                // Contract compliance and ruler-to-ruler opinion both shape faction recruitment.
                // A neglected or overtaxed vassal can organize even before personal relations collapse.
                double op = opinion(sr.id, lr.id) + bonus + (contractualLoyalty - 50) * 0.5;
                Faction cur = factionOf(L.id, s);
                if (cur == null && op < -25 && cooldown.getOrDefault("faction|" + L.id + "|" + s, 0) <= year()) {
                    if (f == null) { f = new Faction(nextId++, "independence", L.id, (int) day); f.leader = s; factions.put(f.id, f); say(realmName(s) + " затевает заговор против " + realmName(L.id)); }
                    f.members.add(s);
                } else if (cur != null && op > -10) {
                    cur.members.remove(s);
                    if (cur.leader.equals(s) && !cur.members.isEmpty()) cur.leader = cur.members.get(0);
                }
            }
            if (f == null) continue;
            f.members.removeIf(m -> !subs.contains(m));
            if (f.members.isEmpty()) { factions.remove(f.id); continue; }
            double mine = 0, total = weight(L.id);
            for (String s : subs) { total += weight(s); if (f.members.contains(s)) mine += weight(s); }
            f.power = total <= 0 ? 0 : mine / total;
            f.progress = Math.min(100, f.progress + 2 + f.power * 25 + (f.members.size() >= 2 ? 2 : 0));
            if (f.progress >= 100 && f.power >= 0.2) strike(f);
        }
        factions.values().removeIf(x -> x.members.isEmpty());
    }

    private void strike(Faction f) {
        factions.remove(f.id);
        for (String m : f.members) cooldown.put("faction|" + f.liege + "|" + m, year() + 10);
        say("Вассалы " + realmName(f.liege) + " поднимают восстание: " + f.members.size() + " государств");
        for (String m : f.members) hooks.independence(m, f.liege);
    }

    /** Одарить вассала: его мнение о правителе растёт на 5 лет. */
    public void appease(String liegeRealm, String memberRealm) {
        Character lr = ruler(liegeRealm), sr = ruler(memberRealm);
        if (lr == null || sr == null) throw new TitleEditor.EditException("Нет правителя");
        if (!d.realms.get(liegeRealm).subjectIds.contains(memberRealm)) throw new TitleEditor.EditException(realmName(memberRealm) + " не подданный");
        opinions.add(sr.id, lr.id, "Щедрые дары", 20, year() + 5);
        say(realmName(liegeRealm) + " одаривает " + realmName(memberRealm));
    }

    /** Подавить заговор: шанс от интриги совета и малой силы фракции. */
    public boolean crush(String liegeRealm, int factionId) {
        Faction f = factions.get(factionId);
        if (f == null || !f.liege.equals(liegeRealm)) throw new TitleEditor.EditException("Нет такой фракции");
        double intr = 0;
        for (Council.Seat s : Council.seats(reg, defs, liegeRealm)) if (s.attr.equals("intrigue")) intr = Math.max(intr, s.value);
        double chance = Math.max(0.1, Math.min(0.9, 0.55 + 0.04 * (intr - 10) - 0.5 * f.power));
        boolean ok = rnd("crush", factionId * 1000L + day + nextId++).nextDouble() < chance;
        Character lr = ruler(liegeRealm);
        if (ok) {
            for (String m : f.members) { Character sr = ruler(m); if (sr != null && lr != null) opinions.add(sr.id, lr.id, "Подавленный заговор", -25, year() + 5); }
            factions.remove(f.id);
            say(realmName(liegeRealm) + " раскрыл и подавил заговор вассалов");
        } else {
            f.progress = Math.min(100, f.progress + 15);
            for (String m : f.members) { Character sr = ruler(m); if (sr != null && lr != null) opinions.add(sr.id, lr.id, "Неудачная расправа", -10, year() + 3); }
            say("Попытка подавить заговор против " + realmName(liegeRealm) + " провалилась");
        }
        return ok;
    }

    // ------------------------------------------------------------------ козни

    public Scheme startScheme(String owner, String target, Scheme.Kind kind) {
        Character o = reg.get(owner), t = reg.get(target);
        if (o == null || !o.alive) throw new TitleEditor.EditException("Заговорщик недоступен");
        if (t == null || !t.alive) throw new TitleEditor.EditException("Цель недоступна");
        if (o == t) throw new TitleEditor.EditException("Нельзя плести козни против себя");
        int mine = 0;
        for (Scheme s : schemes.values()) if (s.owner.equals(owner)) mine++;
        if (mine >= 2) throw new TitleEditor.EditException("Не более двух козней одновременно");
        for (Scheme s : schemes.values()) if (s.owner.equals(owner) && s.target.equals(target) && s.kind == kind) throw new TitleEditor.EditException("Такие козни уже плетутся");
        Scheme s = new Scheme(nextId++, kind, owner, target, realmOf(o));
        schemes.put(s.id, s);
        return s;
    }

    public void cancelScheme(int id) { schemes.remove(id); }

    /** Шанс успеха, 0..1 (для подсказки в окне). */
    public double schemeChance(Scheme s) {
        Character o = reg.get(s.owner), t = reg.get(s.target);
        if (o == null || t == null) return 0;
        double def = council(realmOf(t)).getOrDefault("plot_defense", 0.0) / 100.0;
        return Math.max(0.05, Math.min(0.95, s.kind.base + 0.04 * (CharacterStats.value(o, "intrigue") - CharacterStats.value(t, "intrigue")) - def));
    }

    private void stepSchemes() {
        for (Scheme s : new ArrayList<>(schemes.values())) {
            Character o = reg.get(s.owner);
            if (o == null || !o.alive) { schemes.remove(s.id); continue; }
            double speed = 1 + 0.05 * (CharacterStats.value(o, "intrigue") - 10) + council(s.ownerRealm).getOrDefault("scheme_power", 0.0) * 0.05;
            s.progress += Math.max(0.3, speed);
            s.secrecy = Math.max(0.05, s.secrecy - 0.003);
            if (s.progress >= s.kind.days) { schemes.remove(s.id); finishScheme(s); }
        }
    }

    private void finishScheme(Scheme s) {
        Character o = reg.get(s.owner), t = reg.get(s.target);
        if (o == null || t == null || !o.alive || !t.alive) return;
        Random r = rnd("scheme", s.id * 1000L + day);
        boolean ok = r.nextDouble() < schemeChance(s);
        boolean exposed = r.nextDouble() > s.secrecy;
        Character tr = ruler(s.ownerRealm);
        switch (s.kind) {
            case BEFRIEND -> {
                if (ok) { opinions.add(t.id, o.id, "Дружба", 25, 0); opinions.add(o.id, t.id, "Дружба", 25, 0); s.result = "успех: теперь они друзья"; }
                else s.result = "неудача: цель осталась равнодушной";
            }
            case SWAY -> {
                if (ok) { if (tr != null) opinions.add(t.id, tr.id, "Склонён к союзу", 30, year() + 10); opinions.add(t.id, o.id, "Склонён", 30, year() + 10); s.result = "успех: цель на вашей стороне"; }
                else s.result = "неудача";
            }
            case MURDER -> {
                if (ok) { die(t, exposed ? "убит" : "погиб при странных обстоятельствах"); s.result = "успех: цель мертва"; }
                else s.result = "неудача: цель жива";
            }
        }
        if (exposed) {
            s.exposed = true;
            opinions.add(t.id, o.id, "Раскрытые козни: " + s.kind.title.toLowerCase(), -50, year() + 20);
            Character trr = ruler(realmOf(t));
            if (trr != null && trr != t) opinions.add(trr.id, o.id, "Раскрытые козни против двора", -40, year() + 20);
            s.result += " (раскрыто)";
        }
        say("Козни «" + s.kind.title + "»: " + o.name + " → " + t.name + ": " + s.result);
    }

    // ------------------------------------------------------------------ события

    private boolean cond(EventDef e, Character c, String realm) {
        for (Map.Entry<String, Object> en : e.cond.entrySet()) {
            Object v = en.getValue();
            TitleData.Realm re = d.realms.get(realm);
            switch (en.getKey()) {
                case "trait" -> { if (!CharacterStats.traits(c).contains(String.valueOf(v))) return false; }
                case "no_trait" -> { if (CharacterStats.traits(c).contains(String.valueOf(v))) return false; }
                case "min_age" -> { if (year() - c.birthYear < ((Number) v).intValue()) return false; }
                case "max_age" -> { if (year() - c.birthYear > ((Number) v).intValue()) return false; }
                case "married" -> { if ((!c.spouse.isEmpty() && reg.get(c.spouse) != null && reg.get(c.spouse).alive) != Boolean.TRUE.equals(v)) return false; }
                case "children" -> { boolean has = c.children.stream().map(reg::get).anyMatch(k -> k != null && k.alive); if (has != Boolean.TRUE.equals(v)) return false; }
                case "liege" -> { if ((re != null && re.overlordId != null) != Boolean.TRUE.equals(v)) return false; }
                case "vassals" -> { if ((re != null && !re.subjectIds.isEmpty()) != Boolean.TRUE.equals(v)) return false; }
                case "at_war" -> { if (hooks.atWar(realm) != Boolean.TRUE.equals(v)) return false; }
                case "role" -> { if (!c.hasRole(String.valueOf(v))) return false; }
                default -> { }
            }
        }
        return true;
    }

    private void rollEvents() {
        for (TitleData.Realm re : d.realmList()) {
            Character rl = ruler(re.id);
            if (rl == null || !rl.alive) continue;
            boolean human = humans.contains(re.id);
            if (human && pending.stream().anyMatch(p -> p.realm.equals(re.id))) continue;
            Random r = rnd("event", re.id.hashCode() + day);
            for (EventDef e : events.values()) {
                Character who = rl;
                if (e.who.equals("council")) {
                    List<Character> cs = new ArrayList<>();
                    for (Council.Seat s : Council.seats(reg, defs, re.id)) if (s.holder != null) cs.add(s.holder);
                    if (cs.isEmpty()) continue;
                    who = cs.get(r.nextInt(cs.size()));
                }
                if (cooldown.getOrDefault(who.id + "|" + e.id, 0) > year() || !cond(e, who, re.id)) continue;
                if (r.nextDouble() * 100 >= e.weight) continue;
                cooldown.put(who.id + "|" + e.id, year() + e.cooldownYears);
                if (human) { pending.add(new Pending(nextId++, re.id, who.id, e.id, day)); say("Событие для " + realmName(re.id) + ": " + e.title); }
                else resolveAi(e, who, re.id, r);
                break;                                                    // не больше одного события на государство в месяц
            }
        }
    }

    private void resolveAi(EventDef e, Character who, String realm, Random r) {
        double tot = 0;
        for (EventDef.Option o : e.options) tot += o.ai;
        double x = r.nextDouble() * tot;
        for (EventDef.Option o : e.options) { x -= o.ai; if (x <= 0) { apply(o, who, realm); return; } }
        apply(e.options.get(0), who, realm);
    }

    public String text(Pending p) {
        EventDef e = events.get(p.event);
        Character c = reg.get(p.character);
        return e == null || c == null ? "" : e.text.replace("{name}", c.name).replace("{realm}", realmName(p.realm));
    }

    /** Игрок отвечает на событие. */
    public void choose(int pendingId, int option) {
        Pending p = null;
        for (Pending x : pending) if (x.id == pendingId) p = x;
        if (p == null) throw new TitleEditor.EditException("Нет такого события");
        EventDef e = events.get(p.event);
        Character c = reg.get(p.character);
        if (e == null || option < 0 || option >= e.options.size()) throw new TitleEditor.EditException("Нет такого ответа");
        pending.remove(p);
        if (c != null && c.alive) apply(e.options.get(option), c, p.realm);
    }

    @SuppressWarnings("unchecked")
    private void apply(EventDef.Option o, Character c, String realm) {
        for (Map<String, Object> fx : o.effects) {
            String op = String.valueOf(fx.get("op"));
            switch (op) {
                case "trait" -> {
                    if (c.traits.isEmpty()) c.traits.addAll(CharacterStats.traits(c));
                    if (fx.get("add") != null && CharacterStats.trait(String.valueOf(fx.get("add"))) != null) c.traits.add(String.valueOf(fx.get("add")));
                    if (fx.get("remove") != null) c.traits.remove(String.valueOf(fx.get("remove")));
                }
                case "stat" -> {
                    String a = String.valueOf(fx.get("attr"));
                    c.props.put("stat." + a, Math.max(0, Math.min(20, CharacterStats.base(c, a) + ((Number) fx.get("delta")).intValue())));
                }
                case "opinion" -> {
                    int val = ((Number) fx.get("value")).intValue(), yrs = fx.get("years") instanceof Number n ? n.intValue() : 5;
                    String label = String.valueOf(fx.getOrDefault("label", "Событие"));
                    String who = String.valueOf(fx.get("who"));
                    TitleData.Realm re = d.realms.get(realm);
                    List<Character> from = new ArrayList<>();
                    switch (who) {
                        case "liege" -> { if (re != null && re.overlordId != null) from.add(ruler(re.overlordId)); }
                        case "vassals" -> { if (re != null) for (String s : re.subjectIds) from.add(ruler(s)); }
                        case "spouse" -> { if (!c.spouse.isEmpty()) from.add(reg.get(c.spouse)); }
                        case "council" -> { for (Council.Seat s : Council.seats(reg, defs, realm)) if (s.holder != null && s.holder != c) from.add(s.holder); }
                        default -> { }
                    }
                    for (Character f : from) if (f != null && f != c) opinions.add(f.id, c.id, label, val, yrs <= 0 ? 0 : year() + yrs);
                }
                case "kill" -> die(c, String.valueOf(fx.getOrDefault("cause", "погиб")));
                case "gold" -> hooks.gold(realm, ((Number) fx.get("value")).doubleValue());
                case "child" -> { Character sp = c.spouse.isEmpty() ? null : reg.get(c.spouse); if (sp != null && sp.alive) { Character f = c.gender.equals("m") ? c : sp, m = c.gender.equals("m") ? sp : c; bear(f, m); } }
                default -> { }
            }
        }
        say(c.name + ": " + o.text);
    }

    // ------------------------------------------------------------------ связь с другими аддонами

    /** Пишет эффекты совета в свойства государств: council.income_pct, council.levy_pct, council.commander и т.д. */
    public void syncProps() {
        if (ed == null) return;
        for (TitleData.Realm r : d.realmList()) {
            for (Map.Entry<String, Double> e : council(r.id).entrySet()) {
                double v = Math.round(e.getValue() * 10) / 10.0;
                ed.setDerivedProp(r.id, "council." + e.getKey(), v);
            }
            ed.setDerivedProp(r.id, "people.ties", String.join(",", Marriages.ties(this, r.id)));
            // Publish the canonical ruler-to-ruler opinion to wf-realms through the shared
            // realm property bus; wf-realms consumes it for its monthly vassal contract review.
            Character vassalRuler = ruler(r.id);
            String liegeId = r.overlord == null ? "" : r.overlord.id;
            Character liegeRuler = liegeId.isEmpty() ? null : ruler(liegeId);
            int liegeOpinion = vassalRuler == null || liegeRuler == null ? 0
                    : opinion(vassalRuler.id, liegeRuler.id);
            ed.setDerivedProp(r.id, "politics.liege_opinion", liegeOpinion);
        }
    }

    // ------------------------------------------------------------------ окно на клиенте

    /** Срез двора для окна государства realm: нужные персонажи (правители, совет, козни, события) и связанные с ними записи. Возвращает {реестр, двор} в JSON. */
    public String[] viewJson(String realm) { return viewJson(realm, ""); }

    /** То же для конкретного игрока: в срез попадает его герой и поле me. */
    public String[] viewJson(String realm, String player) {
        Set<String> ids = new LinkedHashSet<>();
        String mine = players.getOrDefault(player, "");
        if (!mine.isEmpty() && reg.get(mine) != null) ids.add(mine);
        for (TitleData.Realm r : d.realmList()) { Character c = ruler(r.id); if (c != null) ids.add(c.id); }
        for (Council.Seat st : Council.seats(reg, defs, realm)) if (st.holder != null) ids.add(st.holder.id);
        Character me = ruler(realm);
        for (Scheme sc : schemes.values()) if (me != null && sc.owner.equals(me.id)) { ids.add(sc.owner); ids.add(sc.target); }
        for (Pending p : pending) if (p.realm.equals(realm)) ids.add(p.character);
        List<Character> own = Marriages.candidates(this, realm);
        for (int i = 0; i < own.size() && i < 4; i++) ids.add(own.get(i).id);
        for (TitleData.Realm r : d.realmList()) {
            if (r.id.equals(realm)) continue;
            List<Character> cs = Marriages.candidates(this, r.id);
            for (int i = 0; i < cs.size() && i < 1; i++) ids.add(cs.get(i).id);
        }
        CourtSim v = new CourtSim(d, null, reg, defs, names, seed, startYear);
        v.day = day;
        v.humans.addAll(humans);
        v.me = mine;
        for (Opinions.Modifier m : opinions.mods) if (ids.contains(m.from) && ids.contains(m.to)) v.opinions.mods.add(m);
        for (Faction f : factions.values()) if (f.liege.equals(realm) || f.members.contains(realm)) v.factions.put(f.id, f);
        for (Scheme sc : schemes.values()) if (me != null && sc.owner.equals(me.id)) v.schemes.put(sc.id, sc);
        for (Pending p : pending) if (p.realm.equals(realm)) v.pending.add(p);
        int from = Math.max(0, news.size() - 80);
        v.news.addAll(news.subList(from, news.size()));
        return new String[]{reg.toJson(ids), v.toJson()};
    }

    // ------------------------------------------------------------------ сохранение

    public String toJson() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("format", "wf-court/1");
        root.put("day", day); root.put("next", nextId);
        root.put("humans", new ArrayList<Object>(humans)); root.put("players", new LinkedHashMap<String, Object>(players)); root.put("me", me);
        root.put("cooldown", new LinkedHashMap<String, Object>(cooldown));
        List<Object> mods = new ArrayList<>();
        for (Opinions.Modifier m : opinions.mods) mods.add(new ArrayList<Object>(List.of(m.from, m.to, m.label, m.value, m.until)));
        root.put("mods", mods);
        List<Object> fl = new ArrayList<>();
        for (Faction f : factions.values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", f.id); m.put("type", f.type); m.put("liege", f.liege); m.put("members", new ArrayList<Object>(f.members)); m.put("power", f.power);
            m.put("leader", f.leader); m.put("formed", f.formedDay); m.put("progress", f.progress);
            fl.add(m);
        }
        root.put("factions", fl);
        List<Object> sl = new ArrayList<>();
        for (Scheme s : schemes.values()) sl.add(new ArrayList<Object>(List.of(s.id, s.kind.name(), s.owner, s.target, s.ownerRealm, s.progress, s.secrecy)));
        root.put("schemes", sl);
        List<Object> pl = new ArrayList<>();
        for (Pending p : pending) pl.add(new ArrayList<Object>(List.of(p.id, p.realm, p.character, p.event, p.day)));
        root.put("pending", pl);
        root.put("news", new ArrayList<Object>(news));
        return Json.write(root);
    }

    @SuppressWarnings("unchecked")
    public void fromJson(String json) {
        Map<String, Object> root = (Map<String, Object>) Json.parse(json);
        if (!"wf-court/1".equals(root.get("format"))) throw new IllegalArgumentException("Неверный формат двора");
        day = ((Number) root.get("day")).longValue(); nextId = ((Number) root.get("next")).intValue();
        humans.clear(); cooldown.clear(); opinions.mods.clear(); factions.clear(); schemes.clear(); pending.clear(); news.clear();
        for (Object o : (List<Object>) root.get("humans")) humans.add((String) o);
        players.clear();
        if (root.get("players") instanceof Map<?, ?> pm) for (var e : pm.entrySet()) players.put(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
        me = root.get("me") == null ? "" : String.valueOf(root.get("me"));
        for (Map.Entry<String, Object> e : ((Map<String, Object>) root.get("cooldown")).entrySet()) cooldown.put(e.getKey(), ((Number) e.getValue()).intValue());
        for (Object o : (List<Object>) root.get("mods")) { List<Object> l = (List<Object>) o; opinions.mods.add(new Opinions.Modifier((String) l.get(0), (String) l.get(1), (String) l.get(2), ((Number) l.get(3)).intValue(), ((Number) l.get(4)).intValue())); }
        for (Object o : (List<Object>) root.get("factions")) {
            Map<String, Object> m = (Map<String, Object>) o;
            Faction f = new Faction(((Number) m.get("id")).intValue(), (String) m.get("type"), (String) m.get("liege"), ((Number) m.get("formed")).intValue());
            for (Object x : (List<Object>) m.get("members")) f.members.add((String) x);
            f.power = ((Number) m.get("power")).doubleValue(); f.leader = (String) m.get("leader"); f.progress = ((Number) m.get("progress")).doubleValue();
            factions.put(f.id, f);
        }
        for (Object o : (List<Object>) root.get("schemes")) {
            List<Object> l = (List<Object>) o;
            Scheme s = new Scheme(((Number) l.get(0)).intValue(), Scheme.Kind.valueOf((String) l.get(1)), (String) l.get(2), (String) l.get(3), (String) l.get(4));
            s.progress = ((Number) l.get(5)).doubleValue(); s.secrecy = ((Number) l.get(6)).doubleValue();
            schemes.put(s.id, s);
        }
        for (Object o : (List<Object>) root.get("pending")) { List<Object> l = (List<Object>) o; pending.add(new Pending(((Number) l.get(0)).intValue(), (String) l.get(1), (String) l.get(2), (String) l.get(3), ((Number) l.get(4)).longValue())); }
        for (Object o : (List<Object>) root.get("news")) news.add((String) o);
    }
}
