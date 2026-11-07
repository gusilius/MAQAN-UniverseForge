package dev.worldforge.tests;

import dev.worldforge.dialogue.*;
import dev.worldforge.people.Character;
import dev.worldforge.people.*;
import dev.worldforge.people.bridge.DialogueBridge;
import dev.worldforge.relations.Relations;
import dev.worldforge.relations.bridge.RelationsBridge;

import java.nio.file.*;
import java.util.*;

public class RelationsTest {
    static int fails;
    static void ok(boolean c, String what) { System.out.println((c ? "ok   " : "FAIL ") + what); if (!c) fails++; }

    static final class Fake extends Relations.Hooks0 {
        Map<String, Integer> inv = new HashMap<>();
        List<String> ran = new ArrayList<>();
        int gift;
        @Override public int count(String p, String i) { return inv.getOrDefault(i, 0); }
        @Override public boolean take(String p, String i, int n) { if (count(p, i) < n) return false; inv.merge(i, -n, Integer::sum); return true; }
        @Override public void give(String p, String i, int n) { inv.merge(i, n, Integer::sum); }
        @Override public String realmOf(String p) { return "stark"; }
        @Override public String run(String p, String a, String r, List<String> args) { ran.add(a + "@" + r + ":" + args); return "ok"; }
        @Override public int takeGift(String p) { int g = gift; gift = 0; return g; }
        @Override public List<String> rumors() { return List.of("Война на севере."); }
    }

    static Character mk(CharacterRegistry reg, String name, String... traits) {
        Character c = reg.create(name, "Дом", "x", "f", 280);
        for (String t : traits) c.traits.add(t);
        return c;
    }

    public static void main(String[] a) throws Exception {
        CharacterRegistry reg = new CharacterRegistry();
        Character n = mk(reg, "Мира", "gregarious"), shy = mk(reg, "Тихон", "shy", "cruel");
        Relations r = new Relations();
        Fake h = new Fake(); r.hooks = h;
        Random rng = new Random(5);
        String P = "p1";

        Relations.Result c1 = r.interact(P, n, "chat", 1, rng);
        ok(c1.ok && c1.delta == 4, "общительный: болтовня +4 (" + c1.delta + ")");
        ok(!r.interact(P, n, "chat", 1, rng).ok, "перезарядка: второй раз в тот же день нельзя");
        ok(r.interact(P, n, "chat", 2, rng).ok, "на следующий день можно");
        ok(!r.interact(P, n, "flirt", 3, rng).ok, "флирт закрыт при низкой симпатии");
        ok(r.interact(P, shy, "insult", 1, rng).delta < 0, "оскорбление портит отношение");
        // шутка: общительный смеётся чаще жестокого
        int g = 0, c = 0;
        for (int i = 0; i < 200; i++) {
            Relations rr = new Relations();
            if (rr.interact("x", n, "joke", 1, new Random(i)).delta > 0) g++;
            if (rr.interact("y", shy, "joke", 1, new Random(i)).delta > 0) c++;
        }
        ok(g > c + 40, "шутки: общительному смешнее (" + g + " против " + c + ")");

        // подарки: убывающая отдача
        Relations.Result g1 = r.gift(P, n, 6, 5);
        r.gift(P, n, 6, 5); r.gift(P, n, 6, 5);
        Relations.Result g4 = r.gift(P, n, 6, 5);
        ok(g1.delta > 5 && g4.delta < g1.delta / 2, "подарки: четвёртый за день почти не радует (" + g1.delta + " -> " + g4.delta + ")");
        ok(!r.gift(P, n, 0, 6).ok, "пустая рука не подарок");

        // роман: нужен путь flirt -> date -> marry
        for (int day = 10; day < 60 && r.bond(P, n.id).love < 60; day += 2) r.interact(P, n, "flirt", day, new Random(day));
        Relations.Bond b = r.bond(P, n.id);
        b.affinity = 45; b.love = 20;
        ok(r.canDate(P, n) != null, "рано встречаться: чувства слабы");
        b.love = 40;
        ok(r.startDating(P, n) && b.dating, "встречаться можно");
        ok(r.canMarry(P, n) != null && !r.marry(P, n, 70), "брак рано");
        b.affinity = 65; b.love = 65;
        ok(r.marry(P, n, 70) && b.married && r.spouseOf(P).equals(n.id), "свадьба");
        ok(!r.ready(P, n, "flirt", 100), "женатым флиртовать незачем");
        Character other = mk(reg, "Лика");
        r.bond(P, other.id).affinity = 80; r.bond(P, other.id).love = 80;
        ok(r.canDate(P, other) != null, "в браке нельзя встречаться с другой");
        r.divorce(P, n);
        ok(!b.married && b.affinity <= -20 && r.spouseOf(P).isEmpty(), "развод портит отношения");

        // найм
        Character w = mk(reg, "Ганс");
        ok(r.canHire(P, w) != null, "незнакомец не пойдёт на службу");
        r.bond(P, w.id).affinity = 30;
        ok(r.hire(P, w, 1) && w.props.get("employer").equals(P) && r.bond(P, w.id).wage > 0, "найм при симпатии 25+");
        r.dismiss(P, w);
        ok(w.props.get("employer") == null, "увольнение");

        // просьбы
        Relations.Request q = r.offerRequest(w, 1, new Random(3));
        ok(q != null && r.offerRequest(w, 2, new Random(9)) == q, "одна открытая просьба на жителя");
        ok(!r.requestReady(P, w.id, 2), "без предметов не готово");
        h.inv.put(q.item, q.count);
        int aff0 = r.bond(P, w.id).affinity;
        Relations.Result done = r.completeRequest(P, w, 3);
        ok(done.ok && h.inv.get(q.item) == 0 && h.inv.get("gold") == q.reward && r.bond(P, w.id).affinity == aff0 + q.aff, "просьба выполнена: награда и симпатия");
        ok(r.openRequest(w.id) == null, "просьба закрыта");
        Relations.Request q2 = r.offerRequest(w, 3, new Random(4));
        ok(!r.completeRequest(P, w, 3 + Relations.REQUEST_DAYS + 1).ok, "просроченная просьба не принимается");

        // затухание
        Relations r2 = new Relations(); r2.bond("z", n.id).affinity = 20; r2.bond("z", n.id).lastContact = 0;
        for (int d = 1; d <= 60; d++) r2.tick(d);
        int af = r2.bond("z", n.id).affinity;
        ok(af < 20 && af >= 0, "без общения симпатия остывает до нуля, но не ниже (" + af + ")");

        // сохранение
        Relations.Request q3 = r.offerRequest(w, 100, new Random(8));
        Relations r3 = new Relations(); r3.fromJson(r.toJson());
        ok(r3.bond(P, w.id).wage == r.bond(P, w.id).wage && r3.openRequest(w.id) != null && r3.openRequest(w.id).item.equals(q3.item), "save/load");

        String vj = r3.viewJson(P, reg::get, x -> "", 101);
        ok(vj.contains("Ганс") && vj.contains("\"req\""), "viewJson: знакомые и открытая просьба");

        // ---- диалоги ----
        DialogueApi.resetForTests();
        RelationsBridge.relations = new Relations(); RelationsBridge.relations.hooks = h; r = RelationsBridge.relations;
        RelationsBridge.npcs = reg::get; RelationsBridge.today = () -> 7; RelationsBridge.rng = new Random(1);
        DialogueBridge.register(); RelationsBridge.register();
        dev.worldforge.quests.bridge.QuestsDialogueBridge.quests = new dev.worldforge.quests.Quests(new dev.worldforge.quests.QuestDefs()); dev.worldforge.quests.bridge.QuestsDialogueBridge.register();
        dev.worldforge.trade.bridge.TradeDialogueBridge.trade = new dev.worldforge.trade.Trade(new dev.worldforge.trade.TradeDefs()); dev.worldforge.trade.bridge.TradeDialogueBridge.register();
        Path dir = Paths.get("wf-dialogue/src/main/resources/data/wf_dialogue/wf_dialogue");
        Map<String, Dialogue> all = new TreeMap<>();
        try (var s = Files.list(dir)) {
            for (Path p : (Iterable<Path>) s.sorted()::iterator) {
                Dialogue d = Dialogue.parse(Files.readString(p));
                List<String> bad = new ArrayList<>();
                for (String x : d.validate()) if (!x.startsWith("Предупреждение")) bad.add(x);
                ok(bad.isEmpty(), "диалог " + d.id + " корректен " + bad);
                all.put(d.id, d);
            }
        }
        Character vil = mk(reg, "Борис");
        Ctx ctx = new Ctx(new HashSet<>()).var("npc", vil.name).var("npc_id", vil.id).var("player", "Стив").var("player_id", P).var("npc_title", vil.name)
                .var("hearts", "").var("mood", "").var("reply", "");
        Session s = new Session(all.get("wf_people:villager"), ctx);
        List<String> ch = s.view().choices;
        ok(ch.contains("Пообщаться по душам"), "в диалоге жителя есть общение: " + ch);
        s.choose(ch.indexOf("Пообщаться по душам"));
        ch = s.view().choices;
        ok(ch.contains("Поболтать") && !ch.contains("Флиртовать") && !ch.contains("Предложить руку и сердце"), "меню общения скрывает недоступное: " + ch);
        s.choose(ch.indexOf("Поболтать"));
        ok(s.view().text.length() > 3 && r.bond(P, vil.id).affinity > 0, "болтовня меняет симпатию: " + s.view().text);
        s.choose(0);   // Далее -> меню
        ok(!s.view().choices.contains("Поболтать"), "после разговора кнопка недоступна до завтра");
        // просьба через диалог
        ch = s.view().choices;
        s.choose(ch.indexOf("Нет ли для меня дела?"));
        ok(!s.view().text.isEmpty() && r.openRequest(vil.id) != null, "просьба появилась: " + s.view().text);
        // поручение маршалу
        Character mar = mk(reg, "Эрик"); mar.posts.add(new Character.Post("marshal", "realm:stark"));
        Ctx c2 = new Ctx(new HashSet<>()).var("npc", mar.name).var("npc_id", mar.id).var("player", "Стив").var("player_id", P).var("npc_title", "Маршал Эрик").var("npc_realm_id", "stark");
        Session s2 = new Session(all.get("wf_people:marshal"), c2);
        ch = s2.view().choices;
        ok(ch.contains("Отдать распоряжение"), "маршал принимает распоряжения: " + ch);
        s2.choose(ch.indexOf("Отдать распоряжение"));
        ch = s2.view().choices;
        s2.choose(ch.indexOf("Созвать ополчение (50 человек)"));
        ok(h.ran.contains("army_raise@stark:[levy, 50]"), "поручение превратилось в действие карты: " + h.ran);
        ok(s2.view().text.contains("ополчение"), "ответ советника: " + s2.view().text);
        System.out.println(fails == 0 ? "ALL OK" : fails + " FAIL");
        if (fails > 0) System.exit(1);
    }
}
