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
        if (failures > 0) throw new AssertionError(failures + " resident task graph tests failed");
    }
}
