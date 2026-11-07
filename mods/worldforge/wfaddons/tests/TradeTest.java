package dev.worldforge.tests;

import dev.worldforge.mod.api.DemoApi;
import dev.worldforge.mod.api.TreasuryApi;
import dev.worldforge.trade.*;

import java.nio.file.*;
import java.util.*;

public class TradeTest {
    static int fails;
    static void ok(boolean c, String w) { System.out.println((c ? "ok   " : "FAIL ") + w); if (!c) fails++; }

    public static void main(String[] a) throws Exception {
        DemoApi.resetForTests(); TreasuryApi.resetForTests();
        TradeDefs d = new TradeDefs();
        d.load(Files.readString(Paths.get("wf-trade/src/main/resources/data/wf_trade/wf_trade/goods.json")));
        ok(d.validate().isEmpty(), "данные валидны " + d.validate());
        ok(d.goods.size() >= 40, "товаров: " + d.goods.size());
        ok(d.goods.get("black_lotus").contraband(), "контрабанда помечена");

        DemoApi.registerTerrain("t", r -> switch (r) { case "field" -> Map.of("plains", 1.0); case "peak" -> Map.of("mountains", 1.0); case "port" -> Map.of("coast", 1.0); default -> Map.of(); });
        DemoApi.registerRaces("t", r -> switch (r) { case "peak" -> Map.of("dwarf", 1.0); case "port" -> Map.of("triton", 0.5, "human", 0.5); default -> Map.of("human", 1.0); });

        Trade t = new Trade(d);
        Map<String, Double> tre = new HashMap<>();
        Set<String> war = new HashSet<>();
        Map<String, Integer> inv = new HashMap<>();
        int[] gold = {1000};
        double[] danger = {0.0};
        t.hooks = new Trade.Hooks() {
            @Override public List<String> neighbors(String r) { return r.equals("field") ? List.of("peak", "port") : List.of("field"); }
            @Override public double distance(String x, String y) { return 3; }
            @Override public boolean atWar(String x, String y) { return war.contains(x + ">" + y) || war.contains(y + ">" + x); }
            @Override public double danger(String x, String y) { return danger[0]; }
            @Override public int count(String o, String i) { return inv.getOrDefault(i, 0); }
            @Override public boolean take(String o, String i, int n) { inv.merge(i, -n, Integer::sum); return true; }
            @Override public void give(String o, String i, int n) { inv.merge(i, n, Integer::sum); }
            @Override public int gold(String o) { return gold[0]; }
            @Override public boolean takeGold(String o, int n) { gold[0] -= n; return true; }
            @Override public void giveGold(String o, int n) { gold[0] += n; }
        };
        t.rng = new Random(5);
        List<String> realms = List.of("field", "peak", "port");
        for (int day = 1; day <= 40; day++) { t.hooks = t.hooks; for (String r : realms) t.market(r).autoTrade = false; t.tick(day, realms); }

        // местность
        ok(t.price("field", "grain") < t.price("peak", "grain"), "зерно дешевле на равнине: " + t.price("field", "grain") + " < " + t.price("peak", "grain"));
        ok(t.price("peak", "iron") < t.price("field", "iron"), "железо дешевле в горах: " + t.price("peak", "iron") + " < " + t.price("field", "iron"));
        ok(t.price("port", "fish") < t.price("peak", "fish"), "рыба дешевле на побережье");
        ok(t.price("field", "furs") > d.goods.get("furs").base(), "меха на равнине дефицитны");
        // раса
        ok(t.consumption("peak", d.goods.get("ale")) > t.consumption("field", d.goods.get("ale")) * 1.8, "гномы пьют больше эля");
        ok(t.consumption("port", d.goods.get("fish")) > t.consumption("field", d.goods.get("fish")), "тритоны едят больше рыбы");
        // котировка
        double b1 = t.quote("field", "grain", 1, true), b20 = t.quote("field", "grain", 20, true), s20 = t.quote("field", "grain", 20, false);
        ok(b20 / 20 > b1, "скупка поднимает цену");
        ok(s20 < b20, "продаём дешевле, чем покупаем");
        // сезон
        double pw = t.production("field", d.goods.get("grain"));
        t.hooks = new Trade.Hooks() { @Override public int season() { return 3; } };
        double pwin = t.production("field", d.goods.get("grain"));
        ok(pwin < pw * 0.6, "зимой зерна меньше: " + pw + " -> " + pwin);
        // потрясение
        double before = t.production("field", d.goods.get("grain"));
        t.shock("field", "grain", 0.2, 5);
        ok(t.production("field", d.goods.get("grain")) < before * 0.3, "неурожай режет выпуск");
        t.shocks.clear();

        // возвращаем хуки
        final Trade.Hooks H = new Trade.Hooks() {
            @Override public List<String> neighbors(String r) { return r.equals("field") ? List.of("peak", "port") : List.of("field"); }
            @Override public double distance(String x, String y) { return 3; }
            @Override public boolean atWar(String x, String y) { return war.contains(x + ">" + y) || war.contains(y + ">" + x); }
            @Override public double danger(String x, String y) { return danger[0]; }
            @Override public int count(String o, String i) { return inv.getOrDefault(i, 0); }
            @Override public boolean take(String o, String i, int n) { inv.merge(i, -n, Integer::sum); return true; }
            @Override public void give(String o, String i, int n) { inv.merge(i, n, Integer::sum); }
            @Override public int gold(String o) { return gold[0]; }
            @Override public boolean takeGold(String o, int n) { gold[0] -= n; return true; }
            @Override public void giveGold(String o, int n) { gold[0] += n; }
        };
        t.hooks = H;

        // караван игрока
        String P = "p:1";
        int g0 = gold[0];
        double stock0 = t.market("peak").stock.get("iron");
        Trade.Caravan c = t.dispatch(P, "peak", "field", "iron", 20, 2, 41);
        ok(c != null && gold[0] < g0, "караван снаряжён: " + t.lastError);
        ok(t.market("peak").stock.get("iron") == stock0 - 20, "товар ушёл с рынка");
        ok(t.dispatch(P, "peak", "field", "iron", 200, 0, 41) == null && t.lastError.contains("Не помещается"), "вместимость: " + t.lastError);
        ok(t.dispatch(P, "peak", "field", "iron", 5, 11, 41) == null, "охрана не больше 10");
        gold[0] = 3; ok(t.dispatch(P, "peak", "field", "iron", 10, 0, 41) == null && t.lastError.contains("денег"), "нет денег"); gold[0] = g0 - 100;
        double tre0 = TreasuryApi.balance("field");
        for (int day = 42; day <= 45; day++) t.tick(day, realms);
        ok(c.status.equals("доставлен"), "доставлен: " + c.status);
        ok(TreasuryApi.balance("field") > tre0, "пошлина в казну: " + (TreasuryApi.balance("field") - tre0));
        ok(c.revenue > c.spent, "горное железо на равнине приносит прибыль: " + Math.round(c.revenue) + " > " + Math.round(c.spent));

        // эмбарго и война
        t.setEmbargo("field", "peak", true);
        ok(t.dispatch(P, "peak", "field", "iron", 5, 0, 46) == null && t.lastError.contains("закрыл") || t.lastError.contains("запретил"), "эмбарго: " + t.lastError);
        t.setEmbargo("field", "peak", false);
        war.add("peak>field");
        ok(t.dispatch(P, "peak", "field", "iron", 5, 0, 46) == null && t.lastError.contains("война"), "война: " + t.lastError);
        war.clear();
        // война в пути
        Trade.Caravan w = t.dispatch(P, "peak", "field", "iron", 5, 0, 46);
        war.add("peak>field"); int g1 = gold[0];
        t.tick(47, realms);
        ok(w.status.startsWith("вернулся") && gold[0] > g1, "война во время пути: " + w.status);
        war.clear();

        // нападение
        danger[0] = 1.0;
        Trade.Caravan r1 = t.dispatch(P, "peak", "field", "iron", 10, 0, 50);
        t.tick(51, realms);
        ok(r1.status.equals("разграблен") && r1.cargo.isEmpty(), "ограбление");
        ok(t.ambushes.size() == 1, "засада создана");
        Trade.Ambush amb = t.ambushes.values().iterator().next();
        ok(amb.strength >= 3 && amb.cargo.get("iron") == 10 && amb.progress > 0 && amb.expires == 51 + Trade.AMBUSH_DAYS, "засада: груз у разбойников " + amb.strength + " " + amb.progress);
        Trade t3 = new Trade(d); t3.hooks = t.hooks; t3.fromJson(t.toJson());
        ok(t3.ambushes.size() == 1 && t3.ambushes.get(amb.id).cargo.get("iron") == 10, "засада сохраняется");
        for (int k = 0; k < amb.strength - 1; k++) ok(t.slay(amb.id, 52) == null && t.ambushes.containsKey(amb.id), "бой идёт: убит " + (k + 1));
        Trade t4 = new Trade(d); t4.hooks = t.hooks; t4.fromJson(t.toJson());
        ok(t4.ambushes.get(amb.id).slain == amb.strength - 1, "число убитых сохраняется");
        ok(t4.slay(amb.id, 52) != null && t4.ambushes.isEmpty(), "последний разбойник - добыча возвращена автоматически");
        t.ambushes.put(amb.id, amb);
        double stockBefore = t.market("peak").stock.get("iron"); int gBefore = gold[0];
        ok(t.recover(amb.id, 53) != null && t.ambushes.isEmpty(), "добыча отбита");
        ok(t.market("peak").stock.get("iron") == stockBefore + 10 && gold[0] > gBefore && r1.status.equals("добыча возвращена"), "груз вернулся, деньги выплачены");
        ok(t.recover(amb.id, 53) == null, "дважды отбить нельзя");
        Trade.Caravan r1b = t.dispatch(P, "peak", "field", "iron", 10, 0, 54);
        t.tick(55, realms);
        ok(t.ambushes.size() == 1, "вторая засада");
        List<String> evx = t.tick(55 + Trade.AMBUSH_DAYS + 1, realms);
        ok(t.ambushes.isEmpty() && evx.stream().anyMatch(x -> x.startsWith("ambush:") && x.endsWith(":expired")), "добыча пропала через срок");
        Trade.Caravan r2 = t.dispatch(P, "peak", "field", "iron", 10, 8, 52);
        double p8 = 1.0 * Math.max(0.2, 1 - 8 * 0.08);
        t.rng = new Random(1);
        t.tick(53, realms);
        ok(r2.status.equals("отбит частично") || r2.status.equals("в пути") || r2.status.equals("доставлен"), "охрана: " + r2.status + " (шанс " + p8 + ")");
        danger[0] = 0;
        for (int day = 54; day < 60; day++) t.tick(day, realms);

        // путь по цепочке провинций
        java.util.Map<Integer, int[]> graph = Map.of(1, new int[]{2}, 2, new int[]{1, 3, 5}, 3, new int[]{2, 4}, 4, new int[]{3}, 5, new int[]{2, 6}, 6, new int[]{5}, 9, new int[]{});
        ok(CaravanPath.bfs(graph::get, 1, 4, 20).equals(List.of(1, 2, 3, 4)), "bfs: кратчайший путь " + CaravanPath.bfs(graph::get, 1, 4, 20));
        ok(CaravanPath.bfs(graph::get, 1, 9, 20).isEmpty(), "bfs: нет пути");
        ok(CaravanPath.bfs(graph::get, 1, 4, 2).isEmpty(), "bfs: предел длины");
        ok(CaravanPath.bfs(graph::get, 7, 7, 5).equals(List.of(7)), "bfs: тот же город");
        double[][] poly = {{0, 0}, {100, 0}, {100, 100}};
        ok(Math.abs(CaravanPath.length(poly) - 200) < 1e-9, "длина ломаной");
        CaravanPath.Pos q1 = CaravanPath.along(poly, 0.25), q2 = CaravanPath.along(poly, 0.75), q3 = CaravanPath.along(poly, 1.0);
        ok(Math.abs(q1.x() - 50) < 1e-6 && Math.abs(q1.z()) < 1e-6, "четверть пути на первом отрезке");
        ok(Math.abs(q2.x() - 100) < 1e-6 && Math.abs(q2.z() - 50) < 1e-6, "три четверти - на втором, за поворотом");
        ok(Math.abs(q3.x() - 100) < 1e-6 && Math.abs(q3.z() - 100) < 1e-6, "конец пути");
        ok(Math.abs(q1.yaw() - (-90)) < 1e-6 && Math.abs(q2.yaw()) < 1e-6, "курс меняется на повороте " + q1.yaw() + " " + q2.yaw());

        // контрабанда
        t.market("peak").stock.put("poison", 100.0);
        int seized = 0, passed = 0;
        for (int i = 0; i < 20; i++) {
            t.market("peak").stock.put("poison", 100.0); gold[0] = 5000;
            Trade.Caravan k = t.dispatch(P, "peak", "port", "poison", 10, 0, 60 + i * 5);
            if (k == null) { System.out.println(t.lastError); break; }
            for (int day = 61 + i * 5; day < 66 + i * 5; day++) t.tick(day, realms);
            if (k.status.contains("конфиск")) seized++; else if (k.status.equals("доставлен")) passed++;
        }
        ok(seized > 0 && passed > 0, "контрабанду иногда изымают: " + seized + "/" + (seized + passed));
        ok(t.tariffFor("peak", "port", d.goods.get("poison")) > t.tariffFor("peak", "port", d.goods.get("iron")) * 2, "пошлина на контрабанду выше");

        // пошлина и договор
        try { t.setTariff("field", 99); ok(false, "пошлина > максимума отвергнута"); } catch (Trade.TradeException e) { ok(true, "пошлина > максимума отвергнута"); }
        t.setTariff("field", 20);
        double hi = t.tariffFor("peak", "field", d.goods.get("iron"));
        tre.put("x", 0.0);
        Trade.Hooks base = t.hooks;
        t.hooks = new Trade.Hooks() { @Override public double treaty(String x, String y) { return 15; } };
        double lo = t.tariffFor("peak", "field", d.goods.get("iron"));
        ok(lo < hi && Math.abs(lo - 0.05) < 1e-9, "договор снижает пошлину: " + hi + " -> " + lo);
        t.hooks = base;
        t.setTariff("field", 5);

        // ИИ-купцы выравнивают цены
        for (String r : realms) t.market(r).autoTrade = true;
        double gap0 = Math.abs(t.price("peak", "iron") - t.price("field", "iron"));
        double cap0 = t.capital.getOrDefault("peak", 0.0);
        t.rng = new Random(11);
        int delivered = 0;
        for (int day = 100; day < 220; day++) t.tick(day, realms);
        for (Trade.Caravan k : t.caravans.values()) if (k.owner.startsWith("m:") && k.status.equals("доставлен")) delivered++;
        ok(t.chronicle.stream().filter(s -> s.contains("доставлен")).count() > 10, "купцы ходят сами: хроника " + t.chronicle.size());
        ok(t.income("field")[0] > 0, "пошлины накапливаются: " + t.income("field")[0]);
        double gap1 = Math.abs(t.price("peak", "iron") - t.price("field", "iron"));
        ok(gap1 <= gap0 + 1e-9 || gap1 < 20, "цены сближаются: " + Math.round(gap0 * 10) / 10.0 + " -> " + Math.round(gap1 * 10) / 10.0);
        ok(t.income("peak")[1] + t.income("field")[1] + t.income("port")[1] > 0, "гильдейский налог в казну");

        // личная торговля
        gold[0] = 500; inv.put("wheat", 50);
        double stk = t.market("field").stock.get("grain");
        ok(t.playerSell(P, "field", "grain", 30) && inv.get("wheat") == 20 && gold[0] > 500, "продажа зерна: золото " + gold[0]);
        ok(t.market("field").stock.get("grain") == stk + 30, "запас рынка вырос");
        int gb = gold[0];
        ok(t.playerBuy(P, "field", "grain", 10) && inv.get("wheat") == 30 && gold[0] < gb, "покупка зерна");
        ok(!t.playerSell(P, "field", "wine", 1), "нет предмета у вина: " + t.lastError);
        ok(!t.playerBuy(P, "field", "poison", 1), "контрабанду нельзя купить на рынке");
        gold[0] = 0; ok(!t.playerBuy(P, "field", "grain", 5) && t.lastError.contains("золота"), "нет золота");

        // диалоги: цены, маршрут, сделки
        dev.worldforge.trade.bridge.TradeDialogueBridge.trade = t;
        String pr = dev.worldforge.trade.bridge.TradeDialogueBridge.prices("field");
        ok(pr.contains("избытке") && pr.contains("Дорого"), "реплика о ценах: " + pr.replace('\n', '|'));
        String rt = dev.worldforge.trade.bridge.TradeDialogueBridge.route("field");
        ok(rt.startsWith("Везите") || rt.contains("нет"), "реплика о маршруте: " + rt);
        gold[0] = 500; inv.put("wheat", 30);
        String ds = dev.worldforge.trade.bridge.TradeDialogueBridge.deal(P, "field", "grain", 10, true);
        ok(ds.startsWith("Продано") && inv.get("wheat") == 20, "продажа через диалог: " + ds);
        ok(dev.worldforge.trade.bridge.TradeDialogueBridge.deal(P, "field", "grain", 999, false).length() > 0 && !dev.worldforge.trade.bridge.TradeDialogueBridge.deal(P, "field", "grain", 999, false).startsWith("Куплено"), "слишком большая покупка отвергнута");

        // отчёт и сохранение
        String v = t.viewJson(P, "field", realms);
        ok(v.contains("\"goods\"") && v.contains("iron") && v.contains("caravans"), "вид");
        String js = t.toJson();
        Trade t2 = new Trade(d); t2.hooks = t.hooks; t2.fromJson(js);
        ok(Math.abs(t2.price("field", "iron") - t.price("field", "iron")) < 1e-9, "цены после загрузки совпадают");
        ok(t2.caravans.size() == t.caravans.size() && t2.chronicle.size() == t.chronicle.size(), "караваны и хроника сохранены");
        ok(t2.market("field").tariff == 5 && t2.income("field")[0] == t.income("field")[0], "рынок сохранён");
        System.out.println(fails == 0 ? "ALL OK" : "FAILS: " + fails);
        System.exit(fails == 0 ? 0 : 1);
    }
}
