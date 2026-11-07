package dev.worldforge.tests;

import dev.worldforge.settle.*;

import java.nio.file.*;
import java.util.*;

public class TradeNetTest {
    static int fails;
    static void ok(boolean c, String what) { System.out.println((c ? "ok   " : "FAIL ") + what); if (!c) fails++; }

    static Settlement city(String id, Defs d) {
        Settlement s = new Settlement(id, id, d);
        s.add("wood", 100); s.add("planks", 60); s.add("stone", 60); s.add("food", 150); s.add("silver", 300);
        for (int i = 0; i < 6; i++) s.addWorker(null);
        s.place("house", 0, 0); s.place("market", 20, 0);
        s.tick(250);
        return s;
    }

    public static void main(String[] a) throws Exception {
        Defs d = new Defs();
        for (String f : new String[]{"base", "extra", "economy"}) d.add(Files.readString(Path.of("wf-settlements/src/main/resources/data/wf_settle/wf_settle/" + f + ".json")));
        Map<String, Settlement> cs = new LinkedHashMap<>();
        Settlement A = city("A", d), B = city("B", d);
        cs.put("A", A); cs.put("B", B);
        A.stock.put("wood", 150.0); B.stock.put("wood", 12.0);
        ok(A.price("wood") < B.price("wood"), "в A дрова дешевле: " + A.price("wood") + " < " + B.price("wood"));
        TradeNetwork n = new TradeNetwork();
        var sug = TradeNetwork.suggestions(cs, 3);
        ok(!sug.isEmpty() && sug.get(0)[0].equals("A") && sug.get(0)[1].equals("B") && sug.get(0)[2].equals("wood"), "подсказка: возить дрова из A в B");
        try { n.add(cs, "A", "A", "wood", 5); ok(false, "в себя нельзя"); } catch (TradeNetwork.TradeException e) { ok(true, "в себя нельзя"); }
        try { n.add(cs, "A", "B", "silver", 5); ok(false, "серебро не возят"); } catch (TradeNetwork.TradeException e) { ok(true, "серебро не возят"); }
        var r = n.add(cs, "A", "B", "wood", 10);
        try { n.add(cs, "A", "B", "wood", 10); ok(false, "дубликат"); } catch (TradeNetwork.TradeException e) { ok(true, "дубликат отклонён"); }
        double sa = A.amount("silver"), sb = B.amount("silver"), spread0 = B.price("wood") / A.price("wood");
        double wa = A.amount("wood"), wb = B.amount("wood");
        for (int i = 0; i < 20; i++) { A.tradeLeft = 100; B.tradeLeft = 100; n.tick(cs, 30); }
        ok(r.carried > 0 && A.amount("wood") < wa && B.amount("wood") > wb, "караван возит: " + (int) r.carried + " ед.");
        ok(A.amount("silver") > sa - 1 && r.profit > 0, "обе стороны в плюсе, прибыль " + Math.round(r.profit));
        double spread1 = B.price("wood") / A.price("wood");
        ok(spread1 < spread0, "цены сближаются: x" + Math.round(spread0 * 100) / 100.0 + " -> x" + Math.round(spread1 * 100) / 100.0);
        for (int i = 0; i < 60; i++) { A.tradeLeft = 100; B.tradeLeft = 100; n.tick(cs, 30); }
        ok(r.status.equals("нет разницы") || r.status.equals("нет товара") || r.status.equals("нет места"), "когда разница исчезла, караван встаёт: " + r.status);
        // без рынка
        Settlement C = new Settlement("C", "C", d); C.add("wood", 100); cs.put("C", C);
        var r2 = n.add(cs, "C", "B", "wood", 5);
        n.tick(cs, 60);
        ok(r2.status.equals("нет рынка") && r2.carried == 0, "без рынка торговли нет");
        // сохранение
        TradeNetwork back = TradeNetwork.fromJson(n.toJson());
        ok(back.routes.size() == n.routes.size() && back.toJson().equals(n.toJson()), "маршруты сохраняются");
        n.remove(r.id);
        ok(n.routes.size() == 1, "маршрут можно убрать");
        System.out.println(fails == 0 ? "ALL OK" : fails + " FAILED");
        if (fails > 0) System.exit(1);
    }
}
