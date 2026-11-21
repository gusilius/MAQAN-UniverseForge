package dev.worldforge.tests;

import dev.worldforge.mod.api.DemoApi;
import dev.worldforge.mod.api.TreasuryApi;
import dev.worldforge.trade.Trade;
import dev.worldforge.trade.TradeDefs;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Random;

public final class TradeDayBatchTest {
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static Trade create(TradeDefs defs) {
        Trade trade = new Trade(defs);
        trade.rng = new Random(9127);
        trade.hooks = new Trade.Hooks() {
            @Override public List<String> neighbors(String realm) {
                return switch (realm) {
                    case "north" -> List.of("south");
                    case "south" -> List.of("north", "coast");
                    default -> List.of("south");
                };
            }
            @Override public int size(String realm) { return realm.equals("north") ? 140 : 90; }
            @Override public double danger(String from, String to) { return 0.0; }
        };
        for (String realm : List.of("north", "south", "coast")) trade.market(realm).autoTrade = true;
        return trade;
    }

    public static void main(String[] args) throws Exception {
        DemoApi.resetForTests();
        TreasuryApi.resetForTests();
        TradeDefs defs = new TradeDefs();
        defs.load(Files.readString(Path.of("wf-trade/src/main/resources/data/wf_trade/wf_trade/goods.json")));
        DemoApi.registerTerrain("trade-batch-test", realm -> Map.of("plains", 1.0));
        DemoApi.registerRaces("trade-batch-test", realm -> Map.of("human", 1.0));

        Trade synchronous = create(defs);
        Trade batched = create(defs);
        List<String> realms = List.of("north", "south", "coast");
        List<String> expected = synchronous.tick(42, realms);
        List<String> actual = null;
        int calls = 0;
        while (actual == null && calls < 100) {
            actual = batched.advanceDay(42, realms, 1);
            calls++;
        }
        check(actual != null, "bounded day eventually completes");
        check(calls > 1, "small budget spreads work over multiple calls");
        check(expected.equals(actual), "event order is identical");
        check(synchronous.toJson().equals(batched.toJson()), "economic state matches synchronous execution");
        check(!batched.hasPendingDay(), "completed day releases transient snapshot");
        System.out.println("TradeDayBatchTest: OK");
    }
}
