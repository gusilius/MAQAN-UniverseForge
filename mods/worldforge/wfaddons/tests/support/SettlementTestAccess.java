package dev.worldforge.settle;

/** Package-scoped test bridge for validating internal settlement ledgers without reopening production APIs. */
public final class SettlementTestAccess {
    private SettlementTestAccess() { }

    public static void flowRecord(Flow flow, double time, String resource, double quantity, boolean inbound) {
        flow.record(time, resource, quantity, inbound);
    }
    public static void flowRecordTrade(Flow flow, double time, String resource, double quantity, boolean inbound) {
        flow.recordTrade(time, resource, quantity, inbound);
    }
    public static void flowLoad(Flow flow, Object saved) { flow.load(saved); }

    public static ResidentTaskGraph.Receipt residentExtract(ResidentTaskGraph graph, int building, int worker,
            String resource, double quantity, double time) {
        return graph.extract(building, worker, resource, quantity, time);
    }
    public static ResidentTaskGraph.Receipt residentProduce(ResidentTaskGraph graph, int building, int worker,
            String resource, double quantity, double time) {
        return graph.produce(building, worker, resource, quantity, time);
    }
    public static double residentDeliver(ResidentTaskGraph graph, int building, int worker,
            String resource, double quantity, double time) {
        return graph.deliver(building, worker, resource, quantity, time);
    }
    public static double residentConsume(ResidentTaskGraph graph, ResidentTaskGraph.Stage stage, int building,
            int worker, String resource, double quantity, double time) {
        return graph.consume(stage, building, worker, resource, quantity, time);
    }
    public static ResidentTaskGraph.Receipt residentBuilt(ResidentTaskGraph graph, int building, int worker, double time) {
        return graph.built(building, worker, time);
    }
    public static double residentConsumeStock(ResidentTaskGraph graph, String resource, double quantity) {
        return graph.consumeStock(resource, quantity);
    }
    public static void residentLoad(ResidentTaskGraph graph, String saved) { graph.fromJson(saved); }
}
