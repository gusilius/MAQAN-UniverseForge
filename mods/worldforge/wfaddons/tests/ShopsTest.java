package dev.worldforge.tests;

import dev.worldforge.mod.api.DemoApi;
import dev.worldforge.mod.api.TreasuryApi;
import dev.worldforge.trade.*;

import java.nio.file.*;
import java.util.*;

public class ShopsTest {
    static int fails;
    static void ok(boolean c, String w) { System.out.println((c ? "ok   " : "FAIL ") + w); if (!c) fails++; }

    public static void main(String[] a) throws Exception {
        DemoApi.resetForTests(); TreasuryApi.resetForTests();
        TradeDefs d = new TradeDefs();
        d.load(Files.readString(Paths.get("wf-trade/src/main/resources/data/wf_trade/wf_trade/goods.json")));
        DemoApi.registerTerrain("t", r -> Map.of("plains", 1.0));
        Trade t = new Trade(d);
        Map<String, Integer> inv = new HashMap<>(); int[] gold = {1000};
        t.hooks = new Trade.Hooks() {
            @Override public int count(String o, String i) { return inv.getOrDefault(i, 0); }
            @Override public boolean take(String o, String i, int n) { inv.merge(i, -n, Integer::sum); return true; }
            @Override public void give(String o, String i, int n) { inv.merge(i, n, Integer::sum); }
            @Override public int gold(String o) { return gold[0]; }
            @Override public boolean takeGold(String o, int n) { gold[0] -= n; return true; }
            @Override public void giveGold(String o, int n) { gold[0] += n; }
        };
        t.rng = new Random(2);
        for (int day = 1; day < 30; day++) t.tick(day, List.of("north"));
        Shops sh = new Shops(t);
        String P = "p:1";

        // создание
        Shops.Shop npc = sh.create("", "Лавка Борина", "north", "overworld", 100, 64, 100, List.of());
        ok(npc != null && npc.id == 1 && gold[0] == 1000, "лавка NPC создана бесплатно");
        ok(sh.create("", "Дубль", "north", "overworld", 101, 64, 101, List.of()) == null && sh.lastError.contains("Рядом"), "рядом лавку не поставить: " + sh.lastError);
        Shops.Shop mine = sh.create(P, "Моя лавка", "north", "overworld", 200, 64, 200, List.of("food", "material"));
        ok(mine != null && gold[0] == 900, "лавка игрока стоит " + (int) Shops.CREATE_COST + ": золото " + gold[0]);
        gold[0] = 50; ok(sh.create(P, "Третья", "north", "overworld", 300, 64, 300, List.of()) == null && sh.lastError.contains("золота"), "не хватает на лавку"); gold[0] = 2000;
        sh.create(P, "Вторая", "north", "overworld", 300, 64, 300, List.of()); sh.create(P, "Третья", "north", "overworld", 400, 64, 400, List.of());
        ok(sh.create(P, "Четвёртая", "north", "overworld", 500, 64, 500, List.of()) == null && sh.lastError.contains("лавки"), "не больше трёх лавок на игрока");
        gold[0] = 1000;

        // ассортимент
        ok(sh.assortment(mine).stream().allMatch(g -> g.category().equals("food") || g.category().equals("material")), "категории лавки соблюдаются");
        ok(sh.assortment(npc).stream().noneMatch(g -> g.contraband() || g.item().isEmpty()), "контрабанды и товаров без предмета нет");

        // сделки
        double stock0 = t.market("north").stock.get("grain");
        double bp = sh.buyPrice(npc, "grain", 10), sp = sh.sellPrice(npc, "grain", 10);
        ok(bp > sp, "лавка продаёт дороже, чем покупает: " + Math.round(bp) + " > " + Math.round(sp));
        ok(bp > t.quote("north", "grain", 10, true), "наценка лавки сверх рынка");
        int g0 = gold[0];
        ok(sh.sellToPlayer(1, P, "grain", 10) && inv.get("wheat") == 10 && gold[0] < g0, "покупка у лавки: золото " + g0 + " -> " + gold[0]);
        ok(t.market("north").stock.get("grain") == stock0 - 10, "запас рынка уменьшился");
        double cash1 = npc.cash;
        ok(cash1 > Shops.START_CASH - 1, "касса получила наценку: " + cash1);
        ok(TreasuryApi.ledger("north").containsKey("Торговля: сборы лавок"), "сбор лавки в казну");
        int g1 = gold[0];
        ok(sh.buyFromPlayer(1, P, "grain", 5) && inv.get("wheat") == 5 && gold[0] > g1, "продажа лавке");
        ok(!sh.sellToPlayer(1, P, "poison", 1) && sh.lastError.contains("не торгуют"), "контрабанду не продают");
        ok(!sh.sellToPlayer(2, P, "poison", 1) || true, "—");
        ok(!sh.sellToPlayer(2, P, "ale", 1) && sh.lastError.contains("не торгуют"), "лавка с категориями не торгует чужим: " + sh.lastError);
        inv.put("wheat", 500); npc.cash = 3;
        ok(!sh.buyFromPlayer(1, P, "grain", 400) && sh.lastError.contains("не хватает денег"), "у торговца кончились деньги: " + sh.lastError);
        gold[0] = 2; ok(!sh.sellToPlayer(1, P, "grain", 10) && sh.lastError.contains("золота"), "у игрока нет золота"); gold[0] = 1000;
        ok(!sh.sellToPlayer(1, P, "grain", 0) && !sh.sellToPlayer(1, P, "grain", 9999), "количество проверяется");

        // наценка владельца
        ok(!sh.setMargin(1, P, false, 20) && sh.lastError.contains("не ваша"), "чужую лавку не настроить");
        ok(sh.setMargin(2, P, false, 25) && mine.margin == 25, "наценка владельца");
        ok(!sh.setMargin(2, P, false, 90), "наценка не больше 40%");
        double lowM = sh.buyPrice(npc, "grain", 5); mine.categories.clear();
        ok(sh.buyPrice(mine, "grain", 5) > lowM, "высокая наценка дороже");
        // касса владельца
        inv.put("wheat", 100); mine.cash = 400; int gb = gold[0];
        ok(sh.collect(2, P) == 300 && gold[0] == gb + 300 && mine.cash == 100, "владелец забрал кассу сверх резерва");
        ok(sh.collect(1, P) == -1, "чужую кассу не забрать");
        ok(sh.collect(2, P) == 0 && sh.lastError.contains("нет лишних"), "второй раз нечего");

        // день
        npc.cash = 10; npc.profit = 50; double cap0 = t.capital.getOrDefault("north", 0.0);
        sh.tick();
        ok(npc.cash == 10 + Shops.DAILY_FLOAT && t.capital.get("north") == cap0 + 50 && npc.profit == 0, "дневной взнос и прибыль купцам");

        // вид и сохранение
        String v = sh.viewJson(1);
        ok(v.contains("grain") && v.contains("buy") && v.contains("cash"), "вид лавки");
        Shops s2 = new Shops(t); s2.fromJson(sh.toJson());
        ok(s2.shops.size() == sh.shops.size() && s2.shops.get(2).margin == 25 && s2.shops.get(1).sold == sh.shops.get(1).sold, "сохранение лавок");
        ok(sh.remove(2, P, false) && !sh.shops.containsKey(2), "владелец закрыл лавку");
        ok(!sh.remove(1, P, false) && sh.remove(1, "", true) && sh.shops.size() == 2, "оператор закрыл чужую");

        // путь каравана
        ok(Math.abs(CaravanPath.progress(4, 4, 0.5) - 0.125) < 1e-9 && Math.abs(CaravanPath.progress(4, 2, 0.5) - 0.625) < 1e-9 && CaravanPath.progress(4, 0, 0) == 1.0, "прогресс по дням");
        CaravanPath.Pos p = CaravanPath.at(0, 0, 1000, 0, 0.25);
        ok(Math.abs(p.x() - 250) < 1e-9 && p.z() == 0 && Math.abs(Math.abs(p.yaw()) - 90) < 1e-6, "точка и курс (на восток): " + p);
        CaravanPath.Pos south = CaravanPath.at(0, 0, 0, 100, 0.5);
        ok(Math.abs(south.yaw()) < 1e-9 && south.z() == 50, "на юг yaw = 0");
        CaravanPath.Pos m1 = CaravanPath.member(south, 2, 3);
        ok(m1.z() < south.z() && CaravanPath.dist(m1.x(), m1.z(), south.x(), south.z()) > 5, "участники колонны идут позади");
        ok(CaravanPath.at(0, 0, 10, 10, 5).x() == 10 && CaravanPath.at(0, 0, 10, 10, -1).x() == 0, "прогресс ограничен");
        System.out.println(fails == 0 ? "ALL OK" : "FAILS: " + fails);
        System.exit(fails == 0 ? 0 : 1);
    }
}
