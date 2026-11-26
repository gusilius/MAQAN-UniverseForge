package dev.worldforge.tests;

import dev.worldforge.troops.OperationalSupply;

public final class OperationalSupplyPersistenceTest {
    private static int failures;
    private static void check(boolean value, String label) {
        System.out.println((value ? "ok   " : "FAIL ") + label);
        if (!value) failures++;
    }

    public static void main(String[] args) {
        OperationalSupply.Store store = new OperationalSupply.Store();
        OperationalSupply.Report first = store.advance("realm:north:army:7", 10, 1, 4, 4, 120);
        check(first != null && first.status() == OperationalSupply.Status.LOW
                        && first.shortage() == 6,
                "daily logistics consumes available rations and reduces readiness by the actual shortage");
        check(store.advance("realm:north:army:7", 10, 1, 4, 4, 120) == null,
                "the same world day cannot apply ration starvation or recovery twice");
        OperationalSupply.Report recovered = store.advance("realm:north:army:7", 10, 1, 10, 10, 121);
        check(recovered != null && recovered.status() == OperationalSupply.Status.SUPPLIED
                        && store.state("realm:north:army:7").readiness > first.readiness(),
                "full field supply restores readiness on the next world day");

        OperationalSupply.Store restored = new OperationalSupply.Store();
        restored.fromJson(store.toJson());
        check(restored.state("realm:north:army:7").lastProcessedWorldDay == 121
                        && restored.state("realm:north:army:7").readiness == store.state("realm:north:army:7").readiness
                        && restored.advance("realm:north:army:7", 10, 1, 10, 10, 121) == null,
                "readiness and the once-per-day checkpoint survive world restart");

        boolean rejected = false;
        try {
            restored.fromJson("{\"format\":\"wf-operational-supply/1\",\"states\":[{\"id\":\"bad\",\"rations\":0,\"readiness\":2,\"days_without_full_rations\":0,\"consumed\":0,\"last_processed_world_day\":1}]}");
        } catch (IllegalArgumentException expected) { rejected = true; }
        check(rejected, "corrupt readiness values fail closed during recovery");

        if (failures > 0) throw new AssertionError(failures + " OperationalSupplyPersistence tests failed");
        System.out.println("OperationalSupplyPersistence: ALL OK");
    }
}
