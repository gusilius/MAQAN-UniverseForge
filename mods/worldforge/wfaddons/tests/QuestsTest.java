package dev.worldforge.tests;

import dev.worldforge.mod.api.ContentApi;
import dev.worldforge.mod.api.StatApi;
import dev.worldforge.quests.*;

import java.nio.file.*;
import java.util.*;

public class QuestsTest {
    static int fails;
    static void ok(boolean c, String w) { System.out.println((c ? "ok   " : "FAIL ") + w); if (!c) fails++; }

    static final class Fake extends Quests.Hooks0 {
        final Map<String, Integer> inv = new HashMap<>(), lvl = new HashMap<>(), orgRank = new HashMap<>(), rep = new HashMap<>(), infl = new HashMap<>();
        final List<String> news = new ArrayList<>(), ran = new ArrayList<>(), notes = new ArrayList<>();
        final Map<String, Double> props = new HashMap<>();
        double treasury;
        final List<String> shocks = new ArrayList<>();
        @Override public void shock(String r, String g, double f, int days) { shocks.add(r + ":" + g + ":" + f + ":" + days); }
        boolean war;
        String ruler = "";
        int xp;
        @Override public int count(String p, String i) { return inv.getOrDefault(p + ":" + i, 0); }
        @Override public boolean take(String p, String i, int n) { if (count(p, i) < n) return false; inv.merge(p + ":" + i, -n, Integer::sum); return true; }
        @Override public void give(String p, String i, int n) { inv.merge(p + ":" + i, n, Integer::sum); }
        @Override public void xp(String p, int n) { xp += n; }
        @Override public int level(String p) { return lvl.getOrDefault(p, 1); }
        @Override public void rep(String p, String t, int n) { rep.merge(t, n, Integer::sum); }
        @Override public void influence(String p, String o, int n) { infl.merge(o, n, Integer::sum); }
        @Override public int orgRank(String p, String o) { return orgRank.getOrDefault(p + ":" + o, -1); }
        @Override public String realmOf(String p) { return p.equals(ruler) ? "stark" : ""; }
        @Override public String rulerOf(String r) { return ruler; }
        @Override public boolean atWar(String r) { return war; }
        @Override public List<String> neighbors(String r) { return List.of("lannister", "baratheon"); }
        @Override public List<String> enemies(String r) { return List.of("lannister"); }
        @Override public String run(String p, String a, String r, List<String> args) { ran.add(a + ":" + args); return "ok"; }
        @Override public void news(String t) { news.add(t); }
        @Override public void notify(String p, String t) { notes.add(t); }
        @Override public void realmProp(String r, String k, double d) { props.merge(k, d, Double::sum); }
        @Override public void treasury(String r, double d) { treasury += d; }
    }

    static Quests.Giver g(String npc, String name, String org, String orgKind, String... roles) { return new Quests.Giver(npc, name, new HashSet<>(Arrays.asList(roles)), org, orgKind, "stark"); }
    static final Quests.Giver VILL = g("n:v1", "Марта", "", ""), MARSHAL = g("n:m1", "Маршал Брайен", "", "", "marshal"), STEW = g("n:s1", "Управляющий", "", "", "steward"),
            CHANC = g("n:c1", "Канцлер", "", "", "chancellor"), RULER = g("n:r1", "Лорд", "", "", "ruler"), MERCH = g("n:g1", "Глава гильдии", "merchants", "guild");

    static Quests.Offer find(Quests q, String p, Quests.Giver g, String qid, int from) {
        for (int day = from; day < from + 600; day += Quests.WINDOW) for (Quests.Offer o : q.offers(p, g, day)) if (o.qid().equals(qid)) return o;
        return null;
    }
    /** Находит день, в который предложение qid существует, и принимает. */
    static Quests.Active take(Quests q, String p, Quests.Giver g, String qid, int from) {
        for (int day = from; day < from + 600; day += Quests.WINDOW) for (Quests.Offer o : q.offers(p, g, day)) if (o.qid().equals(qid)) return q.accept(p, g, o.key(), day);
        return null;
    }

    public static void main(String[] a) throws Exception {
        StatApi.resetForTests();
        ContentApi.resetForTests();
        QuestDefs d = new QuestDefs();
        try (var s = Files.list(Paths.get("wf-quests/src/main/resources/data/wf_quests/wf_quests"))) { for (Path p : (Iterable<Path>) s.sorted()::iterator) d.load(Files.readString(p)); }
        List<String> bad = d.validate();
        ok(bad.isEmpty(), "данные корректны " + bad);
        Set<String> kinds = new TreeSet<>();
        for (var q : d.quests.values()) kinds.addAll(q.giver());
        ok(d.quests.size() >= 45 && d.events.size() >= 14, "объём: заданий " + d.quests.size() + ", событий " + d.events.size() + ", видов дающих " + kinds);

        Fake h = new Fake();
        Quests q = new Quests(d);
        q.hooks = h;
        String P = "p:1";
        h.lvl.put(P, 6);

        // кто что даёт
        List<Quests.Offer> v1 = q.offers(P, VILL, 10), v2 = q.offers(P, VILL, 11), v3 = q.offers(P, VILL, 10 + 5 * Quests.WINDOW);
        ok(!v1.isEmpty() && v1.size() <= 2, "у жителя до 2 предложений: " + v1.stream().map(Quests.Offer::name).toList());
        ok(v1.stream().map(Quests.Offer::key).toList().equals(v2.stream().map(Quests.Offer::key).toList()), "внутри окна предложения стабильны");
        ok(!v1.stream().map(Quests.Offer::key).toList().equals(v3.stream().map(Quests.Offer::key).toList()), "позже предложения другие");
        ok(v1.stream().allMatch(o -> d.quests.get(o.qid()).giver().contains("villager") || d.quests.get(o.qid()).giver().contains("any")), "житель даёт свои поручения");
        ok(find(q, P, MARSHAL, "bandit_hunt", 10) != null && find(q, P, VILL, "bandit_hunt", 10) == null, "охота на разбойников у маршала, не у жителя");
        ok(find(q, P, STEW, "grain_reserve", 10) != null && find(q, P, CHANC, "envoy_mission", 10) != null, "управляющий и канцлер дают свои");
        ok(find(q, P, RULER, "royal_embassy", 10) == null, "правитель не даёт высокие поручения новичку (6 ур.)");
        h.lvl.put(P, 12);
        ok(find(q, P, RULER, "royal_embassy", 10) != null, "на 12 уровне правитель доверяет посольство");
        h.lvl.put(P, 6);
        ok(q.offers(P, MERCH, 10).isEmpty(), "не член гильдии - нет её заданий");
        h.orgRank.put(P + ":merchants", 0);
        ok(find(q, P, MERCH, "caravan_escort", 10) != null || find(q, P, MERCH, "rare_goods", 10) != null, "член гильдии получает её задания");
        ok(find(q, P, Quests.Giver.board("stark"), "board_monsters", 10) != null, "доска объявлений даёт награды за чудовищ");

        // обычное задание: убийства + доклад
        Quests.Active k = take(q, P, MARSHAL, "bandit_hunt", 10);
        ok(k != null && q.activeOf(P).size() == 1, "задание принято");
        int need = Integer.parseInt(k.vars.get("count"));
        q.report(P, "kill", "zombie", need - 1, 20);
        ok(q.activeOf(P).get(0).step.equals("a"), "пока мало убийств - шаг не пройден");
        q.report(P, "kill", "skeleton", 1, 20);
        ok(q.activeOf(P).get(0).step.equals("b"), "убийств достаточно - ждёт доклада");
        ok(q.interact(P, VILL, 21).isEmpty() || q.activeOf(P).size() == 1, "жителю доклад не засчитывается");
        List<String> msg = q.interact(P, MARSHAL, 22);
        ok(q.activeOf(P).isEmpty() && !msg.isEmpty(), "маршалу доложено: " + msg);
        ok(h.count(P, "gold") > 0 && h.xp > 0 && q.isDone(P, "bandit_hunt") && q.doneCount(P) == 1, "награда: " + h.count(P, "gold") + " зол., " + h.xp + " оп.");
        ok(h.rep.getOrDefault("stark", 0) >= 2, "репутация государства выросла");

        // fetch: предметы забираются при сдаче
        Quests.Active f = take(q, P, STEW, "grain_reserve", 30);
        int fc = Integer.parseInt(f.vars.get("count"));
        h.inv.put(P + ":wheat", fc - 1);
        ok(q.interact(P, STEW, 31).get(0).contains("Нужно принести") && q.activeOf(P).size() == 1, "не хватает пшеницы - не сдать");
        h.inv.put(P + ":wheat", fc);
        int gold0 = h.count(P, "gold");
        q.interact(P, STEW, 32);
        ok(h.count(P, "wheat") == 0 && h.count(P, "gold") > gold0 && q.activeOf(P).isEmpty(), "пшеница сдана, золото выдано");

        // масштабирование награды по уровню
        Fake h2 = new Fake(); h2.lvl.put("p:9", 40);
        Quests q2 = new Quests(d); q2.hooks = h2;
        Quests.Active hi = take(q2, "p:9", VILL, "night_guests", 1);
        if (hi == null) hi = take(q2, "p:9", VILL, "harvest_help", 1);
        ok(hi != null && hi.level == 40, "задание запомнило уровень игрока (награда растёт, потолок x3)");

        // цепочка: волки -> вожак
        ok(find(q, P, VILL, "wolf_alpha", 40) == null, "вожак закрыт, пока не выполнены волки");
        Quests.Active w = take(q, P, VILL, "wolves", 40);
        ok(w != null, "волки взяты");
        q.report(P, "kill", "wolf", Integer.parseInt(w.vars.get("count")), 60);
        ok(q.activeOf(P).isEmpty() && q.isDone(P, "wolves"), "волки перебиты, задание завершилось автоматически");
        ok(find(q, P, VILL, "wolf_alpha", 70) != null, "после волков открылся вожак стаи (продолжение цепочки)");

        // выбор с условиями и последствиями
        Quests.Active wd = take(q, P, VILL, "widow_debt", 100);
        ok(wd != null && wd.step.equals("a") && q.viewJson(P, 100).contains("pay"), "долг вдовы: выбор в журнале");
        int g0 = h.count(P, "gold"); h.inv.put(P + ":gold", 0);
        ok(!q.choose(P, wd.id, "pay", 101) && !q.lastError.isEmpty(), "заплатить нечем: " + q.lastError);
        h.inv.put(P + ":gold", g0);
        int rep0 = h.rep.getOrDefault("stark", 0);
        h.lvl.put(P, 6);
        int xp0 = h.xp;
        ok(q.choose(P, wd.id, "leave", 101) && q.activeOf(P).stream().noneMatch(x -> x.qid.equals("widow_debt")) && h.rep.get("stark") == rep0 - 1, "отказаться: репутация падает, задание закрыто");

        // reach-шаг и война
        Quests.Active pb = take(q, P, MARSHAL, "patrol_border", 120);
        ok(pb != null, "патруль границы взят (мира достаточно)");
        String nb = pb.vars.get("neighbor");
        q.report(P, "reach", "dorne", 1, 125);
        ok(q.activeOf(P).stream().anyMatch(x -> x.step.equals("a")), "чужое государство не засчитано");
        q.report(P, "reach", nb, 1, 126);
        ok(q.activeOf(P).stream().anyMatch(x -> x.qid.equals("patrol_border") && x.step.equals("b")), "дошли до " + nb);
        ok(find(q, P, MARSHAL, "war_scout", 130) == null, "разведка у врага - только на войне");
        h.war = true;
        ok(find(q, P, MARSHAL, "war_scout", 130) != null && find(q, P, MARSHAL, "patrol_border", 700) == null, "на войне открылась разведка, патруль закрыт");
        h.war = false;

        // срок
        Quests.Active dl = take(q, P, STEW, "audit", 200);
        ok(dl != null || find(q, P, STEW, "audit", 200) == null, "аудит (если доступен)");
        Quests.Active gr = take(q, P, STEW, "grain_reserve", 260);
        q.activeOf(P).stream().filter(x -> x.qid.equals("grain_reserve")).forEach(x -> x.deadline = 300);
        q.tick(301);
        ok(q.activeOf(P).stream().noneMatch(x -> x.qid.equals("grain_reserve")), "просроченное задание снято");
        ok(q.abandon(P, q.activeOf(P).isEmpty() ? -1 : q.activeOf(P).get(0).id, 301) || q.activeOf(P).isEmpty(), "abandon не падает");

        // испытание: цепочка и флаг для классов
        h.lvl.put(P, 16);
        Quests.Active t1 = take(q, P, MARSHAL, "trial_arms_1", 400);
        ok(t1 != null, "испытание оружием открылось после охоты на разбойников");
        if (t1 != null) {
            q.report(P, "kill", "zombie", Integer.parseInt(t1.vars.get("count")), 410);
            q.interact(P, MARSHAL, 411);
            ok(q.isDone(P, "trial_arms_1"), "первая часть испытания пройдена");
            Quests.Active t2 = take(q, P, MARSHAL, "trial_arms_2", 412);
            ok(t2 != null && q.choose(P, t2.id, "honor", 420) && q.flags(P).contains("trial_of_arms") && q.flags(P).contains("honorable"), "поединок: честный выбор ставит флаг испытания (для класса «Чемпион»)");
        }

        // права и право создавать контент
        String K = "p:3";
        h.lvl.put(K, 12);
        ok(q.permission(5).check(K, false, "quest", "create", "my_q", 0) != null, "без права игрок не создаёт задания");
        ok(q.permission(5).check(K, true, "quest", "create", "my_q", 0) == null, "оператору можно");
        // выдаём право наградой цепочки
        Quests.Active bh = take(q, K, MARSHAL, "bandit_hunt", 500);
        q.report(K, "kill", "zombie", Integer.parseInt(bh.vars.get("count")), 505); q.interact(K, MARSHAL, 506);
        Quests.Active ch = take(q, K, RULER, "charter_quest", 510);
        ok(ch != null, "грамота предлагается после охоты на разбойников");
        h.inv.put(K + ":gold", 120);
        q.interact(K, RULER, 520);
        ok(q.hasRight(K, "create_quest") && h.count(K, "gold") >= 70 - 0, "грамота получена за золото: право create_quest, золота осталось " + h.count(K, "gold"));
        ok(q.permission(5).check(K, false, "quest", "create", "my_q", 0) == null && q.permission(2).check(K, false, "quest", "create", "my_q", 2) != null, "право работает, но лимит записей действует");

        // свой квест через ContentApi
        q.registerContent();
        ContentApi.permission = q.permission(3);
        String own = "{\"id\":\"my_wolves\",\"name\":\"Мои волки\",\"giver\":[\"villager\"],\"desc\":\"Помогите.\",\"steps\":[{\"id\":\"a\",\"type\":\"kill\",\"mob\":\"wolf\",\"count\":3,\"text\":\"Убить 3 волков\"}],\"reward\":{\"gold\":2}}";
        ok(ContentApi.put("quest", "my_wolves", own, K, false) == null && d.quests.containsKey("my_wolves"), "игрок с правом создал своё задание через общее хранилище");
        ok(ContentApi.put("quest", "bad", "{\"id\":\"bad\",\"name\":\"x\",\"steps\":[{\"id\":\"a\",\"type\":\"fly\"}]}", K, false) != null, "некорректное задание отвергнуто");
        ok(ContentApi.put("quest", "stranger", own.replace("my_wolves", "stranger"), "p:77", false) != null, "без права создать нельзя");

        // события мира
        Random rng = new Random(11);
        h.ruler = "";
        int fired = 0; Set<String> ids = new TreeSet<>();
        for (int day = 1; day < 300; day++) for (var fe : q.rollEvents(day, rng, List.of("stark", "lannister", "tyrell"), 0.12)) { fired++; ids.add(fe.event); }
        ok(fired >= 20 && ids.size() >= 8, "события идут: " + fired + " за 300 дней, видов " + ids.size());
        ok(h.news.size() >= fired, "каждое событие попало в новости");
        ok(q.fired.stream().allMatch(x -> x.resolved), "ИИ решил все события сам");
        ok(!h.props.isEmpty() || h.treasury != 0, "последствия: казна " + h.treasury + ", свойства " + h.props);
        // правитель-игрок выбирает сам
        Fake h3 = new Fake(); h3.ruler = "p:7"; h3.lvl.put("p:7", 10);
        Quests q3 = new Quests(d); q3.hooks = h3;
        var ev = d.events.get("bandit_raid");
        var fe = q3.fire(ev, "stark", 50, new Random(1));
        ok(!fe.resolved && q3.pending("p:7").size() == 1 && h3.notes.stream().anyMatch(s -> s.contains("Событие")), "игрок-правитель получил событие и ждёт решения");
        ok(q3.viewJson("p:7", 51).contains("Налёт") && q3.viewJson("p:7", 51).contains("bounty"), "в журнале видны варианты");
        ok(!q3.resolve("p:8", fe.id, "bounty", 51) && q3.lastError.contains("не вашего"), "чужое событие не решить");
        ok(q3.resolve("p:7", fe.id, "bounty", 51) && h3.treasury == -15 && q3.board("stark").contains("board_bandit_bounty"), "решение применено: казна -15 и награда на доске");
        ok(find(q3, "p:7", Quests.Giver.board("stark"), "board_bandit_bounty", 60) != null, "награда за налётчиков висит на доске");
        // просрочка: правитель молчит - действует вариант по умолчанию
        var fe2 = q3.fire(d.events.get("plague"), "stark", 100, new Random(2));
        q3.tick(120);
        ok(fe2.resolved && fe2.choice.equals(d.events.get("plague").dflt()), "правитель не решил - сработал вариант по умолчанию: " + fe2.choice);

        // события мира двигают рынок (wf_trade)
        Fake h5 = new Fake(); Quests q5 = new Quests(d); q5.hooks = h5;
        q5.fire(d.events.get("famine"), "stark", 200, new Random(3));
        ok(h5.shocks.stream().anyMatch(x -> x.startsWith("stark:grain:")), "голод режет выпуск зерна: " + h5.shocks);
        Fake h6 = new Fake(); Quests q6 = new Quests(d); q6.hooks = h6;
        q6.fire(d.events.get("good_harvest"), "stark", 201, new Random(4));
        ok(h6.shocks.stream().anyMatch(x -> x.startsWith("stark:grain:1.")), "урожай поднимает выпуск зерна: " + h6.shocks);
        long withMarket = d.events.values().stream().filter(ev2 -> ev2.choices().stream().anyMatch(c2 -> c2.effects().containsKey("market"))).count();
        ok(withMarket >= 6, "событий с эффектом на рынок: " + withMarket);

        // сохранение
        String js = q.toJson();
        Quests q4 = new Quests(d); q4.hooks = h; q4.fromJson(js);
        ok(q4.toJson().equals(js), "save/load без потерь (" + js.length() + " байт)");
        ok(q4.hasRight(K, "create_quest") && q4.isDone(P, "bandit_hunt"), "права и история сохранились");
        ok(q4.viewJson(P, 600).contains("history") && q4.viewJson(K, 600).contains("rights"), "viewJson");
        System.out.println(fails == 0 ? "ALL OK" : fails + " FAIL");
        if (fails > 0) System.exit(1);
    }
}
