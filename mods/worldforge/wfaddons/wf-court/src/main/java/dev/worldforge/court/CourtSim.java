package dev.worldforge.court;

import dev.worldforge.people.Character;
import dev.worldforge.people.CharacterRegistry;
import dev.worldforge.people.CharacterStats;
import dev.worldforge.people.CharacterDevelopment;
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

        /** Фракция требует пересмотра феодальных обязательств; true только после доменного подтверждения. */
        default boolean vassalDemands(String liege, List<String> vassals) { return false; }

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
    /** Temporary stewardship of an underage sovereign; both references point to canonical Characters. */
    public static final class Regency {
        public final String realm, heir;
        public String regent;
        public final long startedDay;
        Regency(String realm, String heir, String regent, long startedDay) {
            this.realm = realm; this.heir = heir; this.regent = regent; this.startedDay = startedDay;
        }
    }
    public final Map<String, Regency> regencies = new LinkedHashMap<>();
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
        updateRegencies();
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
            if (age <= 16) CharacterDevelopment.upbringingTick(c,
                    c.father.isEmpty() ? null : reg.get(c.father),
                    c.mother.isEmpty() ? null : reg.get(c.mother), y);
            double p = age < 50 ? 0.003 : Math.min(0.55, 0.01 + Math.pow(age - 50, 2) * 0.0007);
            if (c.traits.contains("craven") || CharacterStats.traits(c).contains("lazy")) p *= 1.05;
            if (rnd("death", c.id.hashCode() + day).nextDouble() < p) die(c, "умер от старости или болезни");
        }
        for (Character c : all) {
            if (!c.alive || !c.gender.equals("f") || c.spouse.isEmpty()) continue;
            Character f = reg.get(c.spouse);
            int age = y - c.birthYear;
            if (f == null || !f.alive || age < 18 || age > 40 || c.children.size() >= 6) continue;
            // ResidentLifeEvents also advances local families on the shared CharacterRegistry.
            // Both owners consult the same persisted marker so one calendar year cannot create
            // two births for the same mother when settlement and court ticks run in either order.
            Object lastBirthValue = c.props.get("family.last_birth_year");
            int lastBirthYear = lastBirthValue instanceof Number n ? n.intValue() : 0;
            if (lastBirthYear > 0 && y - lastBirthYear < 2) continue;
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
        // Shared with wf-settlements' ResidentLifeEvents pass: birth is one canonical
        // Character lifecycle event, regardless of which domain clock reaches the year first.
        mother.props.put("family.last_birth_year", year());
        father.props.put("family.last_birth_year", year());
        mother.props.put("family.last_event", "child-born:" + kid.id);
        father.props.put("family.last_event", "child-born:" + kid.id);
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
            for (Succession.Result result : res) {
                say(c.name + " (" + realmName(realm) + ") " + cause + ". Наследует " + result.heir.name + (result.usurper ? " (узурпатор)" : ""));
                if (result.place.startsWith("realm:")) ensureRegency(result.place.substring(6));
            }
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
        if (wasRuler && ed != null) syncProps();
    }

    // ------------------------------------------------------------------ регентство

    public Regency regency(String realm) { return regencies.get(realm); }

    /** Adult members of the realm court who can legally serve as regent. */
    public List<Character> regencyCandidates(String realm) {
        String place = Courts.realmPlace(realm);
        Character sovereign = ruler(realm);
        List<Character> candidates = new ArrayList<>();
        for (Character candidate : reg.all()) {
            if (!candidate.alive || candidate == sovereign || year() - candidate.birthYear < 16
                    || candidate.posts.stream().noneMatch(post -> post.place.equals(place))) continue;
            candidates.add(candidate);
        }
        candidates.sort(Comparator.comparingInt((Character candidate) -> regentScore(realm, candidate, sovereign)).reversed()
                .thenComparing(candidate -> candidate.id));
        return candidates;
    }

    /** Number of existing council seats that support a regency candidate (no parallel faction registry). */
    public int regencyCouncilSupport(String realm, Character candidate) {
        if (candidate == null) return 0;
        int support = 0;
        for (Council.Seat seat : Council.seats(reg, defs, realm)) {
            Character councillor = seat.holder;
            if (councillor != null && councillor != candidate && opinion(councillor.id, candidate.id) >= -10)
                support++;
        }
        return support;
    }

    private int regentScore(String realm, Character candidate, Character heir) {
        int councilSupport = regencyCouncilSupport(realm, candidate);
        int score = CharacterStats.value(candidate, "diplomacy") * 2
                + CharacterStats.value(candidate, "stewardship") * 2
                + CharacterStats.value(candidate, "intrigue")
                + councilSupport * 12;
        if (heir != null) score += Math.max(-20, Math.min(20, opinion(candidate.id, heir.id) / 3));
        Set<String> traits = new HashSet<>(CharacterStats.traits(candidate));
        if (traits.contains("just")) score += 5;
        if (traits.contains("honorable") || traits.contains("loyal")) score += 4;
        if (traits.contains("ambitious_schemer") || traits.contains("cruel")) score -= 5;
        return score;
    }

    private void ensureRegency(String realm) {
        Character heir = ruler(realm);
        if (heir == null || !heir.alive || year() - heir.birthYear >= 16) {
            regencies.remove(realm);
            return;
        }
        Regency current = regencies.get(realm);
        if (current != null && current.heir.equals(heir.id)) {
            Character regent = reg.get(current.regent);
            if (regent != null && regent.alive && year() - regent.birthYear >= 16) return;
        }
        List<Character> candidates = regencyCandidates(realm);
        if (current != null && current.heir.equals(heir.id) && current.regent.isEmpty() && candidates.isEmpty()) return;
        String regentId = candidates.isEmpty() ? "" : candidates.get(0).id;
        regencies.put(realm, new Regency(realm, heir.id, regentId, current == null ? day : current.startedDay));
        Character regent = regentId.isEmpty() ? null : reg.get(regentId);
        say(regent == null
                ? "В " + realmName(realm) + " началось малолетнее правление " + heir.name + ": двор должен назначить регента"
                : regent.name + " становится регентом при малолетнем правителе " + heir.name + " (" + realmName(realm) + ")");
    }

    /** Explicit appointment is limited to adult members of this realm's existing court. */
    public void appointRegent(String realm, String candidateId) {
        Character heir = ruler(realm), candidate = reg.get(candidateId);
        if (heir == null || !heir.alive || year() - heir.birthYear >= 16)
            throw new TitleEditor.EditException("Регентство сейчас не требуется");
        if (candidate == null || !candidate.alive || candidate == heir || year() - candidate.birthYear < 16
                || candidate.posts.stream().noneMatch(post -> post.place.equals(Courts.realmPlace(realm))))
            throw new TitleEditor.EditException("Регентом может стать только совершеннолетний член этого двора");
        Regency current = regencies.get(realm);
        long started = current == null ? day : current.startedDay;
        regencies.put(realm, new Regency(realm, heir.id, candidate.id, started));
        say(candidate.name + " назначен регентом " + realmName(realm) + " при " + heir.name);
    }

    private void updateRegencies() {
        for (String realm : new ArrayList<>(regencies.keySet())) ensureRegency(realm);
        for (TitleData.Realm realm : d.realmList()) {
            Character sovereign = ruler(realm.id);
            if (sovereign != null && sovereign.alive && year() - sovereign.birthYear < 16
                    && !regencies.containsKey(realm.id)) ensureRegency(realm.id);
        }
    }

    // ------------------------------------------------------------------ фракции

    private double weight(String realm) { TitleData.Realm r = d.realms.get(realm); return r == null ? 0 : Math.max(1, r.areaBlocks); }

    private Faction factionOf(String liege, String member) {
        for (Faction faction : factions.values())
            if (faction.liege.equals(liege) && faction.members.contains(member)) return faction;
        return null;
    }

    private Faction factionFor(String liege, String type) {
        for (Faction faction : factions.values())
            if (faction.liege.equals(liege) && faction.type.equals(type)) return faction;
        return null;
    }

    private static double realmShare(TitleData.Realm realm, String key) {
        if (realm == null) return 0;
        Object raw = realm.props.get(key);
        if (!(raw instanceof Number number) || !Double.isFinite(number.doubleValue())) return 0;
        return Math.max(0, Math.min(1, number.doubleValue()));
    }

    private void updateFactions() {
        for (TitleData.Realm liege : d.realmList()) {
            Character liegeRuler = ruler(liege.id);
            if (liegeRuler == null) continue;
            double councilBonus = council(liege.id).getOrDefault("vassal_opinion", 0.0);
            List<String> subjects = new ArrayList<>(liege.subjectIds);
            subjects.sort(String::compareTo);

            for (String subjectId : subjects) {
                Character vassalRuler = ruler(subjectId);
                if (vassalRuler == null) continue;
                TitleData.Realm vassal = d.realms.get(subjectId);
                int loyalty = vassal != null && vassal.props.get("politics.vassal_loyalty") instanceof Number n
                        ? Math.max(0, Math.min(100, n.intValue())) : 50;
                double taxShare = realmShare(vassal, "tax_share");
                double levyShare = realmShare(vassal, "levy_share");

                // Personal opinion, the canonical Statecraft contract and its visible map projection
                // all contribute to grievance. High obligations can trigger demands before secession.
                double personalityPressure = (CharacterDevelopment.drive(vassalRuler, CharacterDevelopment.Drive.INDEPENDENCE) - 50) * 0.03
                        + (CharacterDevelopment.temperament(vassalRuler, CharacterDevelopment.Temperament.AMBITION) - 50) * 0.02
                        - (CharacterDevelopment.drive(vassalRuler, CharacterDevelopment.Drive.SECURITY) - 50) * 0.01;
                Regency regency = regencies.get(liege.id);
                Character regent = regency == null ? null : reg.get(regency.regent);
                double regentInfluence = regent == null || !regent.alive ? 0
                        : (CharacterStats.value(regent, "diplomacy") - 10) * 0.6
                        + (CharacterStats.value(regent, "stewardship") - 10) * 0.2
                        + (regencyCouncilSupport(liege.id, regent) - 2) * 2.5;
                double adjustedOpinion = opinion(vassalRuler.id, liegeRuler.id) + councilBonus + personalityPressure + regentInfluence
                        + (loyalty - 50) * 0.5
                        - Math.max(0, taxShare - 0.20) * 70.0
                        - Math.max(0, levyShare - 0.30) * 50.0;
                String desiredType = adjustedOpinion <= -55 || loyalty <= 15 ? "independence"
                        : adjustedOpinion <= -25 || loyalty <= 35 ? "demands" : "";

                Faction current = factionOf(liege.id, subjectId);
                if (current != null && !current.type.equals(desiredType)) {
                    current.members.remove(subjectId);
                    if (current.leader.equals(subjectId))
                        current.leader = current.members.isEmpty() ? "" : current.members.get(0);
                    current = null;
                }
                if (desiredType.isEmpty()) continue;
                if (current != null) continue;
                if (cooldown.getOrDefault("faction|" + liege.id + "|" + subjectId, 0) > year()) continue;

                Faction faction = factionFor(liege.id, desiredType);
                if (faction == null) {
                    faction = new Faction(nextId++, desiredType, liege.id, (int) day);
                    factions.put(faction.id, faction);
                    say(realmName(subjectId) + (desiredType.equals("independence")
                            ? " требует независимости от " : " объединяет требования против ") + realmName(liege.id));
                }
                faction.members.add(subjectId);
                if (faction.leader.isEmpty()) faction.leader = subjectId;
            }

            for (Faction faction : new ArrayList<>(factions.values())) {
                if (!faction.liege.equals(liege.id)) continue;
                faction.members.removeIf(member -> !subjects.contains(member) || ruler(member) == null);
                if (faction.members.isEmpty()) {
                    factions.remove(faction.id);
                    continue;
                }
                double factionWeight = 0, totalWeight = weight(liege.id);
                for (String subjectId : subjects) {
                    double subjectWeight = weight(subjectId);
                    totalWeight += subjectWeight;
                    if (faction.members.contains(subjectId)) factionWeight += subjectWeight;
                }
                faction.power = totalWeight <= 0 ? 0 : factionWeight / totalWeight;
                faction.progress = Math.min(100, faction.progress + 2 + faction.power * 25
                        + (faction.members.size() >= 2 ? 2 : 0));
                if (faction.progress >= 100 && faction.power >= 0.2) strike(faction);
            }
        }
        factions.values().removeIf(faction -> faction.members.isEmpty());
    }

    private void strike(Faction faction) {
        if ("demands".equals(faction.type)) {
            List<String> members = List.copyOf(faction.members);
            if (!hooks.vassalDemands(faction.liege, members)) {
                faction.progress = 95;
                say("Требования вассалов " + realmName(faction.liege)
                        + " пока не получили подтверждения политической системы");
                return;
            }
            factions.remove(faction.id);
            for (String member : members)
                cooldown.put("faction|" + faction.liege + "|" + member, year() + 3);
            say("Сюзерен " + realmName(faction.liege)
                    + " уступил требованиям вассалов: феодальные обязательства пересмотрены");
            return;
        }

        factions.remove(faction.id);
        for (String member : faction.members)
            cooldown.put("faction|" + faction.liege + "|" + member, year() + 10);
        say("Вассалы " + realmName(faction.liege) + " поднимают восстание: "
                + faction.members.size() + " государств");
        for (String member : faction.members) hooks.independence(member, faction.liege);
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
        double daring = (CharacterDevelopment.temperament(o, CharacterDevelopment.Temperament.RISK) - 50) * 0.0015;
        double ambition = (CharacterDevelopment.temperament(o, CharacterDevelopment.Temperament.AMBITION) - 50) * 0.001;
        double guile = o.traits.contains("deceitful") || o.traits.contains("shrewd") || o.traits.contains("ambitious_schemer") ? 0.035 : 0.0;
        double vigilance = (CharacterDevelopment.drive(t, CharacterDevelopment.Drive.SECURITY) - 50) * 0.001
                + (t.traits.contains("paranoid") || t.traits.contains("observant") ? 0.035 : 0.0);
        return Math.max(0.05, Math.min(0.95, s.kind.base
                + 0.04 * (CharacterStats.value(o, "intrigue") - CharacterStats.value(t, "intrigue"))
                + daring + ambition + guile - vigilance - def));
    }

    private void stepSchemes() {
        for (Scheme s : new ArrayList<>(schemes.values())) {
            Character o = reg.get(s.owner);
            if (o == null || !o.alive) { schemes.remove(s.id); continue; }
            double speed = 1 + 0.05 * (CharacterStats.value(o, "intrigue") - 10)
                    + (CharacterDevelopment.temperament(o, CharacterDevelopment.Temperament.AMBITION) - 50) * 0.004
                    + (CharacterDevelopment.temperament(o, CharacterDevelopment.Temperament.DISCIPLINE) - 50) * 0.002
                    + council(s.ownerRealm).getOrDefault("scheme_power", 0.0) * 0.05;
            s.progress += Math.max(0.3, speed);
            double concealment = o.traits.contains("deceitful") || o.traits.contains("ambitious_schemer") ? 0.001 : 0.0;
            s.secrecy = Math.max(0.05, Math.min(1.0, s.secrecy - 0.003 + concealment));
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
                if (ok) {
                    opinions.add(t.id, o.id, "Дружба", 25, 0); opinions.add(o.id, t.id, "Дружба", 25, 0);
                    CharacterDevelopment.remember(o, "court:befriend:" + s.id, "court_friendship", t.globalId,
                            "успешное сближение", 35, day * 1440L);
                    CharacterDevelopment.remember(t, "court:befriend:" + s.id, "court_friendship", o.globalId,
                            "успешное сближение", 35, day * 1440L);
                    s.result = "успех: теперь они друзья";
                }
                else s.result = "неудача: цель осталась равнодушной";
            }
            case SWAY -> {
                if (ok) { if (tr != null) opinions.add(t.id, tr.id, "Склонён к союзу", 30, year() + 10); opinions.add(t.id, o.id, "Склонён", 30, year() + 10); s.result = "успех: цель на вашей стороне"; }
                else s.result = "неудача";
            }
            case MURDER -> {
                if (ok) {
                    CharacterDevelopment.recordConduct(o, CharacterDevelopment.Conduct.CRUELTY, t,
                            "court:assassination:" + s.id, day * 1440L);
                    CharacterDevelopment.remember(t, "court:assassination:" + s.id, "assassination_attempt",
                            o.globalId, "покушение на жизнь", -100, day * 1440L);
                    die(t, exposed ? "убит" : "погиб при странных обстоятельствах");
                    s.result = "успех: цель мертва";
                }
                else s.result = "неудача: цель жива";
            }
        }
        if (exposed) {
            s.exposed = true;
            CharacterDevelopment.recordConduct(o, CharacterDevelopment.Conduct.BETRAYAL, t,
                    "court:scheme-exposed:" + s.id, day * 1440L);
            CharacterDevelopment.recordExperience(t, CharacterDevelopment.Experience.BETRAYED,
                    "court:betrayal:" + s.id, "раскрытые козни " + o.name, day * 1440L, 2);
            CharacterDevelopment.remember(t, "court:scheme:" + s.id, "court_betrayal", o.globalId,
                    s.kind.title, -70, day * 1440L);
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
            Set<String> dynasticTies = Marriages.ties(this, r.id);
            ed.setDerivedProp(r.id, "people.ties", String.join(",", dynasticTies));
            ed.setDerivedProp(r.id, "politics.dynastic_ties", dynasticTies.size());

            List<Faction> realmFactions = factions.values().stream().filter(f -> f.liege.equals(r.id)).toList();
            double factionPressure = Math.min(1.0, realmFactions.stream().mapToDouble(f -> Math.max(0, f.power)).sum());
            ed.setDerivedProp(r.id, "politics.faction_pressure", Math.round(factionPressure * 1000) / 1000.0);
            ed.setDerivedProp(r.id, "politics.faction_count", realmFactions.size());
            Regency activeRegency = regencies.get(r.id);
            Character regent = activeRegency == null ? null : reg.get(activeRegency.regent);
            ed.setDerivedProp(r.id, "politics.regency.active",
                    activeRegency != null && regent != null && regent.alive ? 1 : 0);
            ed.setDerivedProp(r.id, "politics.regency.heir",
                    activeRegency == null ? "" : activeRegency.heir);
            ed.setDerivedProp(r.id, "politics.regency.regent",
                    regent == null || !regent.alive ? "" : regent.globalId);
            ed.setDerivedProp(r.id, "politics.regency.started_year",
                    activeRegency == null ? 0 : startYear + (int) (activeRegency.startedDay / 365));
            ed.setDerivedProp(r.id, "politics.regency.council_support",
                    regent == null || !regent.alive ? 0 : regencyCouncilSupport(r.id, regent));

            long activeSchemes = schemes.values().stream().filter(sc -> {
                Character target = reg.get(sc.target);
                return sc.ownerRealm.equals(r.id) || (target != null && realmOf(target).equals(r.id));
            }).count();
            double intriguePressure = Math.min(1.0, activeSchemes / 3.0);
            ed.setDerivedProp(r.id, "politics.intrigue_pressure", Math.round(intriguePressure * 1000) / 1000.0);

            Character sovereign = ruler(r.id);
            if (sovereign != null) {
                // The ruler's actual Character Registry attributes and traits shape the realm's
                // strategic preferences. These are derived projections, never a second ruler profile.
                for (String attribute : CharacterStats.ATTRS)
                    ed.setDerivedProp(r.id, "politics.ruler_" + attribute,
                            CharacterStats.value(sovereign, attribute) / 20.0);
                Set<String> rulerTraits = new HashSet<>(CharacterStats.traits(sovereign));
                for (String trait : List.of("greedy", "generous", "just", "cruel", "ambitious",
                        "content", "brave", "craven", "patient", "wroth", "scholar", "shrewd",
                        "honorable", "opportunistic", "loyal", "fickle", "protective", "callous",
                        "disciplined", "impulsive", "pragmatic", "idealistic", "charismatic", "awkward"))
                    ed.setDerivedProp(r.id, "politics.ruler_trait_" + trait, rulerTraits.contains(trait) ? 1.0 : 0.0);
                for (CharacterDevelopment.Drive drive : CharacterDevelopment.Drive.values())
                    ed.setDerivedProp(r.id, "politics.ruler_drive_" + drive.name().toLowerCase(Locale.ROOT),
                            CharacterDevelopment.drive(sovereign, drive) / 100.0);
                for (CharacterDevelopment.Temperament facet : CharacterDevelopment.Temperament.values())
                    ed.setDerivedProp(r.id, "politics.ruler_temperament_" + facet.name().toLowerCase(Locale.ROOT),
                            CharacterDevelopment.temperament(sovereign, facet) / 100.0);
                TitleData.Title highest = null;
                for (String key : r.titleKeys) {
                    TitleData.Title candidate = d.findTitle(key);
                    if (candidate != null && (highest == null || candidate.tier > highest.tier
                            || candidate.tier == highest.tier && candidate.key.compareTo(highest.key) < 0))
                        highest = candidate;
                }
                if (highest != null && !sovereign.globalId.equals(highest.props.get("holderCharacterId")))
                    ed.setProp(highest.id, "holderCharacterId", sovereign.globalId);
            }

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
        for (Character candidate : regencyCandidates(realm)) { if (ids.size() > 48) break; ids.add(candidate.id); }
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
        Regency regency = regencies.get(realm);
        if (regency != null) v.regencies.put(realm, new Regency(regency.realm, regency.heir, regency.regent, regency.startedDay));
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
        List<Object> rl = new ArrayList<>();
        for (Regency r : regencies.values())
            rl.add(new ArrayList<Object>(List.of(r.realm, r.heir, r.regent, r.startedDay)));
        root.put("regencies", rl);
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
        humans.clear(); cooldown.clear(); opinions.mods.clear(); factions.clear(); regencies.clear(); schemes.clear(); pending.clear(); news.clear();
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
        if (root.get("regencies") instanceof List<?> savedRegencies) for (Object o : savedRegencies) {
            List<Object> values = (List<Object>) o;
            Regency regency = new Regency((String) values.get(0), (String) values.get(1),
                    (String) values.get(2), ((Number) values.get(3)).longValue());
            regencies.put(regency.realm, regency);
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
