package dev.worldforge.tests;

import dev.worldforge.settle.*;
import dev.worldforge.mod.economy.TreasuryService;

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
        TradeNetwork autonomous = new TradeNetwork();
        int autonomousCreated = autonomous.ensureAutonomousRoutes(cs, 1, 2, 2.0);
        ok(autonomousCreated == 1 && autonomous.routes.size() == 1,
                "autonomous supply links are created only between staffed markets");
        var autonomousRoute = autonomous.routes.values().iterator().next();
        ok(autonomous.routes.values().stream().filter(route -> route.from.equals(autonomousRoute.from)
                        || route.to.equals(autonomousRoute.from)).count() <= 1
                        && autonomous.routes.values().stream().filter(route -> route.from.equals(autonomousRoute.to)
                        || route.to.equals(autonomousRoute.to)).count() <= 1,
                "autonomous trade participation respects per-settlement hub limits");
        ok(autonomous.ensureAutonomousRoutes(cs, 1, 2, 2.0) == 0,
                "autonomous route review is idempotent when supply capacity is already linked");
        Settlement rich = city("rich", d), richer = city("richer", d);
        rich.stock.put("wood", 30.0); richer.stock.put("wood", 120.0);
        // Demand pressure makes the stocked destination more expensive, so the candidate
        // direction is deliberately rich -> richer; stock checks must still reject it.
        rich.pressure.put("wood", -0.8); richer.pressure.put("wood", 0.8);
        ok(TradeNetwork.suggestions(Map.of("rich", rich, "richer", richer), 4).stream()
                        .noneMatch(route -> route[0].equals("rich") && route[1].equals("richer")
                                && route[2].equals("wood")),
                "trade panel hides a price-spread route when the destination is already stocked");
        TradeNetwork noDemand = new TradeNetwork();
        noDemand.ensureAutonomousRoutes(Map.of("rich", rich, "richer", richer), 2, 2, 2.0);
        ok(noDemand.routes.values().stream().noneMatch(route -> route.from.equals("rich")
                        && route.to.equals("richer") && route.res.equals("wood")),
                "autonomous supply network does not create a route toward a stocked destination");
        Settlement reserve = city("reserve", d), needy = city("needy", d);
        reserve.stock.put("wood", TradeNetwork.RESERVE + 1); needy.stock.put("wood", 0.0);
        reserve.pressure.put("wood", -0.8); needy.pressure.put("wood", 0.8);
        ok(TradeNetwork.suggestions(Map.of("reserve", reserve, "needy", needy), 4).stream()
                        .noneMatch(route -> route[0].equals("reserve") && route[1].equals("needy")
                                && route[2].equals("wood")),
                "trade panel hides a route that would consume the source reserve");
        TradeNetwork noExport = new TradeNetwork();
        noExport.ensureAutonomousRoutes(Map.of("reserve", reserve, "needy", needy), 2, 2, 2.0);
        ok(noExport.routes.values().stream().noneMatch(route -> route.from.equals("reserve")
                        && route.to.equals("needy") && route.res.equals("wood")),
                "autonomous supply network protects the source settlement reserve");
        ok(!sug.isEmpty() && sug.get(0)[0].equals("A") && sug.get(0)[1].equals("B") && sug.get(0)[2].equals("wood"), "подсказка: возить дрова из A в B");
        List<CityRow> projected = CityRow.fromJson(CityRow.toJson(List.of(CityRow.of(A), CityRow.of(B))));
        ok(TradeNetwork.suggestions(projected, 3).stream().anyMatch(route ->
                        route[0].equals("A") && route[1].equals("B") && route[2].equals("wood")),
                "settlement panel projection preserves supply-aware route candidates");
        ok(projected.get(0).capacity.containsKey("wood") && projected.get(0).consumption.containsKey("wood")
                        && projected.get(0).tradeRate > 0,
                "city panel projection includes market, stock capacity and consumption data");
        Flow domesticFlow = new Flow();
        domesticFlow.record(10, "wood", 4, false);
        domesticFlow.recordTrade(10, "wood", 30, false);
        ok(Math.abs(domesticFlow.perMinute(10, "wood", false) - 24) < 1e-9,
                "outbound trade does not inflate domestic consumption statistics");
        ok(Math.abs(domesticFlow.tradePerMinute(10, "wood", false) - 180) < 1e-9,
                "trade throughput remains separately observable");
        Flow restoredFlow = new Flow();
        restoredFlow.load(domesticFlow.save());
        ok(Math.abs(restoredFlow.perMinute(10, "wood", false) - 24) < 1e-9
                        && Math.abs(restoredFlow.tradePerMinute(10, "wood", false) - 180) < 1e-9,
                "domestic and trade flow channels survive save/reload");
        try { n.add(cs, "A", "A", "wood", 5); ok(false, "в себя нельзя"); } catch (TradeNetwork.TradeException e) { ok(true, "в себя нельзя"); }
        try { n.add(cs, "A", "B", "silver", 5); ok(false, "серебро не возят"); } catch (TradeNetwork.TradeException e) { ok(true, "серебро не возят"); }
        try { n.add(cs, "A", "B", "wood", Double.NaN); ok(false, "NaN скорость запрещена"); } catch (TradeNetwork.TradeException e) { ok(true, "NaN скорость запрещена"); }
        try { n.add(cs, "A", "B", "wood", Double.POSITIVE_INFINITY); ok(false, "бесконечная скорость запрещена"); } catch (TradeNetwork.TradeException e) { ok(true, "бесконечная скорость запрещена"); }
        var r = n.add(cs, "A", "B", "wood", 10);
        n.setDuty(r.id, "north", 0.05);
        try { n.add(cs, "A", "B", "wood", 10); ok(false, "дубликат"); } catch (TradeNetwork.TradeException e) { ok(true, "дубликат отклонён"); }
        double accBeforeInvalidTicks = r.acc;
        n.tick(cs, Double.NaN);
        n.tick(cs, Double.POSITIVE_INFINITY);
        n.tick(cs, -1);
        ok(r.acc == accBeforeInvalidTicks && r.carried == 0, "невалидный шаг времени не портит торговый маршрут");
        double sa = A.amount("silver"), sb = B.amount("silver"), spread0 = B.price("wood") / A.price("wood");
        double dutyBefore = TreasuryService.current().balance("realm:north");
        double wa = A.amount("wood"), wb = B.amount("wood");
        A.tradeLeft = 100; B.tradeLeft = 100;
        n.tick(cs, 30);
        var firstShipment = n.shipments.values().stream().filter(shipment -> shipment.routeId == r.id).findFirst().orElse(null);
        ok(firstShipment != null && firstShipment.status.equals("в пути")
                        && A.amount("wood") < wa && Math.abs(B.amount("wood") - wb) < 1e-9,
                "dispatch reserves source goods in transit without instantly crediting the destination");
        ok(firstShipment != null && TreasuryService.current().balance(TradeNetwork.cargoEscrow(firstShipment.id)) > 0,
                "shipment cargo is held in a canonical TreasuryLedger escrow account");
        TradeNetwork inTransitReload = TradeNetwork.fromJson(n.toJson());
        ok(inTransitReload.shipments.size() == n.shipments.size()
                        && inTransitReload.shipments.get(firstShipment.id).status.equals("в пути")
                        && Math.abs(inTransitReload.shipments.get(firstShipment.id).quantity - firstShipment.quantity) < 1e-9,
                "in-flight cargo, route identity and progress state survive save/reload");
        for (int i = 0; i < 20; i++) { A.tradeLeft = 100; B.tradeLeft = 100; n.tick(cs, 30); }
        ok(r.carried > 0 && A.amount("wood") < wa && B.amount("wood") > wb, "караван доставляет: " + (int) r.carried + " ед.");
        double protectedWoodReserve = Math.max(TradeNetwork.RESERVE * 2, A.consumedPerMin("wood") * 120.0);
        ok(A.amount("wood") + 1e-9 >= protectedWoodReserve,
                "trade route never drains the supplier below its two-day domestic-consumption reserve");
        ok(A.flow.tradePerMinute(A.time, "wood", false) > 0,
                "outbound trade is tracked separately from domestic consumption");
        ok(B.flow.tradePerMinute(B.time, "wood", true) > 0,
                "inbound trade is tracked separately from local production");
        ok(A.amount("silver") > sa - 1 && r.profit > 0, "обе стороны в плюсе, прибыль " + Math.round(r.profit));
        ok(TreasuryService.current().balance("realm:north") > dutyBefore,
                "customs duty is transferred into the destination realm treasury");
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
        ok(back.routes.size() == n.routes.size() && back.toJson().equals(n.toJson())
                        && back.routes.get(r.id).dutyRate == 0.05 && back.routes.get(r.id).dutyRealm.equals("north"),
                "маршруты и параметры пошлин сохраняются");
        n.remove(r.id);
        ok(n.routes.size() == 1, "маршрут можно убрать");
        System.out.println(fails == 0 ? "ALL OK" : fails + " FAILED");
        if (fails > 0) System.exit(1);
    }
}
