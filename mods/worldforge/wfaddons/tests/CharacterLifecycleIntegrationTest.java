package dev.worldforge.tests;

import dev.worldforge.settle.Defs;
import dev.worldforge.settle.Settlement;
import dev.worldforge.settle.Worker;

/** Save/load coverage for durable settlement-to-Character lifecycle handoff records. */
public final class CharacterLifecycleIntegrationTest {
    private static int failures;

    private static void ok(boolean condition, String label) {
        System.out.println((condition ? "ok   " : "FAIL ") + label);
        if (!condition) failures++;
    }

    public static void main(String[] args) {
        Settlement settlement = new Settlement("forge", "Forge", new Defs());
        Worker worker = settlement.addWorker("Aldric");
        worker.person = "c42";

        ok(settlement.removeWorkerByPerson("c42", "death:starvation"),
                "worker departure is resolved by canonical Character ID");
        ok(settlement.workers.isEmpty() && settlement.pendingDepartures().size() == 1,
                "departure removes the worker projection but keeps a pending lifecycle event");

        String saved = settlement.toJson();
        ok(saved.contains("wf-settlement/2"), "settlement writes the versioned lifecycle schema");
        Settlement restored = Settlement.fromJson(saved, new Defs());
        ok(restored.pendingDepartures().size() == 1
                        && restored.pendingDepartures().get(0).personId().equals("c42")
                        && restored.pendingDepartures().get(0).reason().equals("death:starvation"),
                "pending Character death survives settlement save/load");

        String legacy = saved.replace("wf-settlement/2", "wf-settlement/1");
        Settlement migrated = Settlement.fromJson(legacy, new Defs());
        ok(migrated.pendingDepartures().size() == 1
                        && migrated.pendingDepartures().get(0).personId().equals("c42"),
                "wf-settlement/1 saves migrate with pending identity references intact");

        migrated.acknowledgeDeparture(migrated.pendingDepartures().get(0));
        ok(migrated.pendingDepartures().isEmpty(), "lifecycle event is removed only after registry acknowledgement");
        System.out.println(failures == 0 ? "ALL OK" : failures + " FAILED");
        if (failures > 0) System.exit(1);
    }
}
