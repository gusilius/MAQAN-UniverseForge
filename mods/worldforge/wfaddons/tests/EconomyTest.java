package dev.worldforge.tests;

import dev.worldforge.settle.*;
import dev.worldforge.mod.Json;
import java.util.*;

import java.nio.file.*;

public class EconomyTest {
    static int fails;

    static void ok(boolean c, String what) { System.out.println((c ? "ok   " : "FAIL ") + what); if (!c) fails++; }

    static boolean orderFails(Runnable r) { try { r.run(); return false; } catch (Settlement.OrderException e) { return true; } }
    static String firstDiff(Object a, Object b, String path) {
        if (Objects.equals(a, b)) return "";
        if (a instanceof Map<?, ?> am && b instanceof Map<?, ?> bm) {
            Set<String> keys = new TreeSet<>();
            am.keySet().forEach(k -> keys.add(String.valueOf(k)));
            bm.keySet().forEach(k -> keys.add(String.valueOf(k)));
            for (String key : keys) {
                String diff = firstDiff(am.get(key), bm.get(key), path + "." + key);
                if (!diff.isEmpty()) return diff;
            }
        }
        if (a instanceof List<?> al && b instanceof List<?> bl) {
            if (al.size() != bl.size()) return path + ".size: " + al.size() + " != " + bl.size();
            for (int i = 0; i < al.size(); i++) {
                String diff = firstDiff(al.get(i), bl.get(i), path + "[" + i + "]");
                if (!diff.isEmpty()) return diff;
            }
        }
        return path + ": " + a + " != " + b;
    }

    static Defs defs() throws Exception {
        Defs d = new Defs();
        for (String f : new String[]{"base", "extra", "economy"}) d.add(Files.readString(Path.of("wf-settlements/src/main/resources/data/wf_settle/wf_settle/" + f + ".json")));
        return d;
    }

    public static void main(String[] a) throws Exception {
        Defs d = defs();
        ok(d.validate().stream().noneMatch(m -> !m.startsWith("Предупреждение")), "набор данных с экономикой без ошибок: " + d.validate());
        Settlement s = new Settlement("e", "Торговая Гавань", d);
        s.add("wood", 200); s.add("planks", 60); s.add("stone", 80); s.add("food", 200); s.add("silver", 20);
        for (int i = 0; i < 6; i++) s.addWorker(null);
        s.place("house", 0, 0); s.place("market", 20, 0);
        s.tick(200);
        ok(s.count("market", true) == 1, "рынок построен");
        double ap2 = s.approval();
        s.setTax(3);
        ok(s.approval() < ap2, "высокий налог портит настроение: " + ap2 + " -> " + s.approval());
        s.setTax(0);
        ok(s.approval() > ap2, "нулевой налог его улучшает");
        s.setTax(2);

        // налоги
        double silver0 = s.amount("silver");
        s.tick(120);
        ok(s.amount("silver") > silver0 - s.upkeepPerMin() * 2, "налоги пополняют казну (налог " + Math.round(s.taxPerMin() * 10) / 10.0 + "/мин, содержание " + s.upkeepPerMin() + "/мин)");
        ok(s.taxPerMin() > 0, "налог положителен");

        // торговля
        s.tick(60);
        ok(s.tradePerMin() > 0 && s.tradeLeft > 0, "рынок даёт торговые операции: " + (int) s.tradeLeft);
        double st = s.amount("stone"), si = s.amount("silver");
        s.sell("stone", 5);
        ok(s.amount("stone") == st - 5 && s.amount("silver") > si, "продажа камня");
        ok(s.sellPrice("stone") < s.price("stone") && s.buyPrice("stone") > s.price("stone"), "наценка рынка");
        s.buy("food", 3);
        ok(orderFails(() -> s.sell("silver", 1)), "серебро нельзя продать");
        ok(orderFails(() -> s.buy("food", 1_000_000)), "нельзя купить сверх запаса операций");
        ok(orderFails(() -> s.buy("nope", 1)), "неизвестный товар отклоняется");

        // автоторговля
        s.setAutoTrade("wood", "sell", 100);
        s.stock.put("wood", 150.0);
        double w = s.amount("wood");
        s.add("wood", 40);
        s.tick(60);
        ok(s.amount("wood") < w + 40, "автопродажа сбывает излишек дерева");
        s.setAutoTrade("wood", "off", 0);
        ok(s.autoSell.isEmpty(), "правило убрано");

        // содержание и комфорт
        s.place("tavern", 30, 10);
        s.tick(200);
        ok(s.count("tavern", true) == 1, "трактир построен");
        ok(s.approvalBreakdown().stream().anyMatch(f -> f.label.startsWith("Трактиры")), "трактир влияет на настроение");
        s.stock.put("silver", 0.0);
        s.tick(5);
        ok(s.buildings.stream().anyMatch(b -> b.unpaid) || s.amount("silver") > 0, "без серебра здания встают");

        // приоритеты работ
        Settlement p = new Settlement("p", "Работы", d);
        p.add("wood", 300); p.add("planks", 50); p.add("stone", 50); p.add("food", 100);
        for (int i = 0; i < 2; i++) p.addWorker(null);
        p.place("lumber_camp", 0, 0); p.place("farm", 20, 0);
        p.tick(150);
        Worker w1 = p.workers.get(0);
        p.setWorkPriority(w1.id, "farmer", 1);
        p.setWorkPriority(w1.id, "lumberjack", 0);
        p.unassign(w1.id);
        w1.profession = "laborer";
        p.tick(10);
        ok(w1.profession.equals("laborer") && w1.workRole().equals("farmer"),
                "общий рабочий выполняет фермерскую работу без потери постоянной профессии: "
                        + w1.profession + "/" + w1.workRole());
        ok(orderFails(() -> p.setWorkPriority(w1.id, "farmer", 9)) && orderFails(() -> p.setWorkPriority(999, "farmer", 1)), "неверные приоритеты отклоняются");

        // сохранение
        s.setTax(1); s.setAutoTrade("stone", "buy", 30);
        String js = s.toJson();
        Settlement back = Settlement.fromJson(js, d);
        boolean economyRoundTrip = back.toJson().equals(js);
        if (!economyRoundTrip) System.out.println("ECONOMY ROUNDTRIP DIFF " + firstDiff(Json.parse(js), Json.parse(back.toJson()), "$"));
        ok(back.taxLevel == 1 && back.autoBuy.get("stone") == 30 && economyRoundTrip, "сохранение экономики совпадает");
        Settlement pb = Settlement.fromJson(p.toJson(), d);
        ok(pb.workers.get(0).jobPrio != null && pb.workers.get(0).jobPrio.get("farmer") == 1, "приоритеты работ сохраняются");

        // динамические цены: дефицит дорожит, избыток дешевит, сделки двигают цену и затухают
        Settlement m = new Settlement("m", "Рынок", d);
        for (int i = 0; i < 6; i++) m.addWorker(null);
        m.add("wood", 5); m.add("silver", 500); m.place("market", 0, 0); m.add("wood", 200); m.add("planks", 100); m.add("stone", 80); m.add("food", 150);
        m.tick(300);
        m.stock.put("wood", 5.0);
        double scarce = m.price("wood");
        m.stock.put("wood", m.capacity("wood"));
        double plenty = m.price("wood");
        ok(scarce > plenty * 1.5, "дефицит дороже избытка: " + scarce + " > " + plenty);
        ok(scarce <= m.basePrice("wood") * 2.0 + 1e-9 && plenty >= m.basePrice("wood") * 0.5 - 1e-9, "цена в границах x0.5..x2");
        m.stock.put("wood", 50.0);
        m.tradeLeft = 100;
        double p0 = m.price("wood");
        m.buy("wood", 5);
        m.stock.put("wood", 50.0);
        ok(m.price("wood") > p0 && m.priceTrend("wood") >= 0, "покупки поднимают цену: " + p0 + " -> " + m.price("wood"));
        for (int i = 0; i < 6; i++) { m.tradeLeft = 100; m.stock.put("wood", 50.0); m.sell("wood", 5); }
        m.stock.put("wood", 50.0);
        ok(m.priceTrend("wood") < 0, "серия продаж роняет цену");
        m.stock.put("wood", 50.0);
        m.tick(3000);
        m.stock.put("wood", 50.0);
        ok(Math.abs(m.pressure.getOrDefault("wood", 0.0)) < 1e-9, "давление сделок затухает");
        Settlement mb = Settlement.fromJson(m.toJson(), d);
        ok(mb.pressure.equals(m.pressure), "давление сохраняется");
        System.out.println(fails == 0 ? "ALL OK" : fails + " FAILED");
        if (fails > 0) System.exit(1);
    }
}
