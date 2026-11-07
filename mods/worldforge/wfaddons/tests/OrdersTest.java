package dev.worldforge.tests;

import dev.worldforge.orders.*;

import java.nio.file.*;
import java.util.*;

public class OrdersTest {
    static int fails;
    static void ok(boolean c, String what) { System.out.println((c ? "ok   " : "FAIL ") + what); if (!c) fails++; }

    static final class Fake extends Orders.Hooks0 {
        Map<String, Integer> inv = new HashMap<>();
        List<String> ran = new ArrayList<>();
        @Override public int count(String p, String i) { return inv.getOrDefault(i, 0); }
        @Override public boolean take(String p, String i, int n) { if (count(p, i) < n) return false; inv.merge(i, -n, Integer::sum); return true; }
        @Override public void give(String p, String i, int n) { inv.merge(i, n, Integer::sum); }
        @Override public String realmOf(String p) { return "stark"; }
        @Override public String run(String p, String a, String r, List<String> args) { ran.add(a + "@" + r + ":" + args); return "ok"; }
    }

    public static void main(String[] a) throws Exception {
        Orders o = new Orders();
        Fake h = new Fake(); o.hooks = h;
        try (var s = Files.list(Paths.get("wf-orders/src/main/resources/data/wf_orders/wf_orders"))) {
            for (Path p : (Iterable<Path>) s.sorted()::iterator) { try { o.load(Files.readString(p)); } catch (Exception e) { ok(false, p + ": " + e); } }
        }
        ok(o.defs.size() == 6, "загружено организаций: " + o.defs.size());
        for (OrgDef d : o.defs.values()) for (String r : d.rivals) ok(o.defs.containsKey(r), d.name + ": соперник " + r + " существует");
        String P = "p";
        Random rng = new Random(1);

        ok(o.canJoin(P, "merchants_guild", 1) != null, "без золота не вступить");
        h.inv.put("gold", 500);
        ok(o.join(P, "merchants_guild", 1) && o.rankOf(P, "merchants_guild") == 0 && h.inv.get("gold") == 460, "вступление за взнос");
        ok(o.canJoin(P, "thieves_guild", 2) != null, "соперник запрещён: " + o.canJoin(P, "thieves_guild", 2));
        ok(o.canJoin(P, "merchants_guild", 2) != null, "дважды нельзя");
        ok(o.join(P, "smiths_guild", 2) && o.orgsOf(P).size() == 2, "вторая организация");
        ok(o.canJoin(P, "night_watch", 2) != null, "третья нельзя");

        // контракты
        List<Orders.Contract> cs = o.offers(P, "merchants_guild", 3, rng);
        ok(cs.size() == Orders.OFFERS, "три предложения");
        for (Orders.Contract c : cs) ok(c.reward > 0 && c.text.indexOf('{') < 0, "контракт заполнен: " + c.text);
        Orders.Contract fetch = null;
        for (Orders.Contract c : cs) if (c.type.equals("fetch")) fetch = c;
        ok(fetch != null, "есть задание на доставку");
        ok(!o.complete(P, fetch.id, 4), "нельзя сдать не взяв");
        o.accept(P, fetch.id);
        ok(!o.complete(P, fetch.id, 4), "без предметов не сдать: " + o.lastError);
        h.inv.put(fetch.item, fetch.count);
        int g0 = h.inv.get("gold"), inf0 = o.member(P, "merchants_guild").influence;
        ok(o.complete(P, fetch.id, 4) && h.inv.get("gold") == g0 + fetch.reward && o.member(P, "merchants_guild").influence == inf0 + fetch.influence, "сдача: золото и влияние");
        ok(!o.complete(P, fetch.id, 4), "дважды не сдать");
        // повышение
        String promoted = o.addInfluence(P, "merchants_guild", 200);
        ok("Фактор".equals(promoted) && o.rankName(P, "merchants_guild").equals("Фактор"), "повышение через несколько званий: " + promoted);
        ok(o.perk(P, "discount_pct") == 8 + 0 && o.perk(P, "tax_relief_pct") == 3, "бонусы званий суммируются: " + o.perk(P, "discount_pct"));
        // деяния
        Orders.Contract deed = null;
        for (Orders.Contract c : o.offers(P, "merchants_guild", 5, rng)) if (c.type.equals("deed")) deed = c;
        if (deed == null) { for (int i = 0; i < 30 && deed == null; i++) { o.leave(P, "smiths_guild", 100 + i); for (Orders.Contract c : o.offers(P, "merchants_guild", 5, new Random(i))) if (c.type.equals("deed")) deed = c; } }
        if (deed != null) {
            o.accept(P, deed.id); o.report(P, "kill_hostile", 3);
            ok(!o.complete(P, deed.id, 6), "деяние не выполнено: " + o.lastError);
            o.report(P, "kill_hostile", 100);
            ok(o.complete(P, deed.id, 6), "деяние выполнено");
        }

        // пожертвование
        int tr = (int) o.state("merchants_guild").treasury;
        h.inv.put("gold", 100);
        ok(o.donate(P, "merchants_guild", 50) && (int) o.state("merchants_guild").treasury >= tr + 50, "пожертвование в казну");

        // прошения
        o.addInfluence(P, "merchants_guild", 20);
        int gold = h.inv.get("gold");
        String msg = o.petition(P, "merchants_guild", "loan", 10);
        ok(msg != null && h.inv.get("gold") == gold + 100, "ссуда из казны");
        ok(o.petition(P, "merchants_guild", "loan", 11) == null && o.lastError.contains("Рано"), "перезарядка прошения: " + o.lastError);
        o.addInfluence(P, "merchants_guild", 100);
        ok(o.petition(P, "merchants_guild", "low_tax", 12) != null && h.ran.contains("econ_tax@stark:[0]"), "прошение запускает действие карты: " + h.ran);

        // взносы и исключение за долги
        h.inv.put("gold", 0);
        for (int d = 20; d <= 80; d++) o.tick(d, rng);
        ok(o.member(P, "merchants_guild") == null, "неплательщика исключили");
        h.inv.put("gold", 100);
        o.join(P, "night_watch", 90); o.leave(P, "night_watch", 91);
        ok(o.canJoin(P, "night_watch", 95).contains("недавно") && o.canJoin(P, "night_watch", 120) == null, "после ухода нельзя вернуться сразу, позже можно");
        ok(o.chronicle.stream().anyMatch(s -> s.contains("за долги")), "хроника помнит исключение");

        // соперничество: сила остаётся в 1..100
        for (int i = 0; i < 50; i++) o.rivalry(rng);
        for (String id : o.defs.keySet()) ok(o.state(id).power >= 1 && o.state(id).power <= 100, id + ": сила в границах " + o.state(id).power);

        // сохранение
        h.inv.put("gold", 300);
        o.join(P, "knights_shield", 200);
        o.offers(P, "knights_shield", 201, rng);
        Orders o2 = new Orders(); for (OrgDef d : o.defs.values()) o2.add(d);
        o2.fromJson(o.toJson());
        ok(o2.rankOf(P, "knights_shield") == 0 && o2.contractsOf(P, "knights_shield").size() == Orders.OFFERS && Math.abs(o2.state("thieves_guild").power - o.state("thieves_guild").power) < 0.001, "save/load");
        String vj = o2.viewJson(P, 202, "stark");
        ok(vj.contains("Орден Щита") && vj.contains("contracts"), "viewJson");
        // диалог гильдмастера
        dev.worldforge.dialogue.DialogueApi.resetForTests();
        dev.worldforge.orders.bridge.OrdersDialogueBridge.orders = o2;
        dev.worldforge.orders.bridge.OrdersDialogueBridge.today = () -> 210;
        dev.worldforge.orders.bridge.OrdersDialogueBridge.register();
        var md = dev.worldforge.dialogue.Dialogue.parse(Files.readString(Paths.get("wf-orders/src/main/resources/data/wf_orders/wf_dialogue/master.json")));
        List<String> bad = new ArrayList<>();
        for (String x : md.validate()) if (!x.startsWith("Предупреждение")) bad.add(x);
        ok(bad.isEmpty(), "диалог мастера корректен " + bad);
        Fake h2 = new Fake(); o2.hooks = h2; h2.inv.put("gold", 400);
        var ctx = new dev.worldforge.dialogue.Ctx(new HashSet<>()).var("npc_org", "smiths_guild").var("player_id", "q").var("npc_title", "Мастер");
        var ses = new dev.worldforge.dialogue.Session(md, ctx);
        ok(ses.view().choices.stream().anyMatch(c -> c.startsWith("Хочу вступить")), "незнакомцу предлагают вступить");
        ses.choose(ses.view().choices.indexOf(ses.view().choices.stream().filter(c -> c.startsWith("Хочу вступить")).findFirst().get()));
        ok(ses.view().text.contains("Добро пожаловать") && o2.rankOf("q", "smiths_guild") == 0, "вступление через диалог: " + ses.view().text);
        ses.choose(0);
        ok(ses.view().choices.contains("Какие у вас есть задания?"), "меню члена организации");
        ses.choose(ses.view().choices.indexOf("Какие у вас есть задания?"));
        ok(ses.view().text.contains("1."), "список заданий: " + ses.view().text.replace('\n', '|'));
        System.out.println(fails == 0 ? "ALL OK" : fails + " FAIL");
        if (fails > 0) System.exit(1);
    }
}
