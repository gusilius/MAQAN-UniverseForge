package dev.worldforge.tests;

import dev.worldforge.settle.ResidentTaskGraph;

public final class ResidentTaskGraphTest {
    private static int failures;
    private static void check(boolean value, String message) {
        if (!value) { failures++; System.out.println("FAIL " + message); }
        else System.out.println("ok   " + message);
    }

    public static void main(String[] args) {
        ResidentTaskGraph graph = new ResidentTaskGraph();
        ResidentTaskGraph.Receipt extracted = graph.extract(7, 11, "wood", 5, 1);
        check(extracted.stage() == ResidentTaskGraph.Stage.EXTRACTED && extracted.workerId() == 11,
                "extract task records the specialist worker and source building");
        check(graph.deliver(7, 22, "wood", 3, 2) == 3,
                "hauler can deliver only goods already extracted at the source");
        check(graph.count(ResidentTaskGraph.Stage.HAULED) == 1
                        && graph.count(ResidentTaskGraph.Stage.STORED) == 1,
                "delivery creates linked haul and storage receipts");
        check(graph.consume(ResidentTaskGraph.Stage.PROCESSED, 9, 33, "wood", 2, 3) == 2,
                "production consumes stored goods through the task graph");
        ResidentTaskGraph.Receipt produced = graph.produce(9, 33, "planks", 2, 3.5);
        check(produced.stage() == ResidentTaskGraph.Stage.PRODUCED && produced.parentReceiptId() > 0,
                "processed output links to the inputs consumed by its production building");
        check(graph.deliver(9, 22, "planks", 1, 4) == 1,
                "processed goods follow the same haul and storage pipeline");
        String saved = graph.toJson();
        ResidentTaskGraph restored = new ResidentTaskGraph();
        restored.fromJson(saved);
        check(restored.count(ResidentTaskGraph.Stage.EXTRACTED) == 1
                        && restored.count(ResidentTaskGraph.Stage.PROCESSED) == 1,
                "task receipts survive save and reload");
        check(restored.consume(ResidentTaskGraph.Stage.CONSTRUCTION, 12, 44, "wood", 1, 4) == 1,
                "construction consumes the remaining stored material lot");
        ResidentTaskGraph.Receipt construction = restored.receipts().get(restored.receipts().size() - 1);
        check(construction.stage() == ResidentTaskGraph.Stage.CONSTRUCTION && construction.parentReceiptId() > 0,
                "construction receipt links back to the stored resource provenance");
        ResidentTaskGraph.Receipt built = restored.built(12, 55, 5);
        check(built.stage() == ResidentTaskGraph.Stage.BUILT && built.parentReceiptId() == construction.id(),
                "completed construction records the worker and links to its committed materials");
        ResidentTaskGraph bounded = new ResidentTaskGraph();
        bounded.extract(3, 7, "stone", 2, 1);
        check(bounded.deliver(3, 8, "stone", 5, 2) == 2,
                "hauling is capped by the actual local resource lot");
        check(bounded.deliver(3, 8, "stone", 1, 3) == 0,
                "hauling cannot create receipts for resources absent from the source");
        check(bounded.consume(ResidentTaskGraph.Stage.CONSTRUCTION, 4, 9, "stone", 5, 4) == 2,
                "consumption is capped by the stored resource lots");
        check(bounded.consume(ResidentTaskGraph.Stage.PROCESSED, 4, 9, "stone", 1, 5) == 0
                        && bounded.count(ResidentTaskGraph.Stage.PROCESSED) == 0,
                "missing inventory cannot be represented as a successful consumption");
        boolean rejectedCorrupt = false;
        try {
            bounded.fromJson("{\\"format\\":\\"wf-resident-task-graph/1\\",\\"next\\":2,\\"receipts\\":[{\\"id\\":1,\\"stage\\":\\"UNKNOWN\\",\\"building\\":1,\\"worker\\":1,\\"resource\\":\\"wood\\",\\"quantity\\":1,\\"parent\\":0,\\"time\\":1}],\\"local\\":{},\\"stored\\":{}}");
        } catch (RuntimeException expected) { rejectedCorrupt = true; }
        check(rejectedCorrupt, "corrupt receipt stages fail closed instead of being silently discarded");
        if (failures > 0) throw new AssertionError(failures + " resident task graph tests failed");
    }
}
