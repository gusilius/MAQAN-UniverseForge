package dev.worldforge.tests;

import dev.worldforge.troops.MilitaryDeathLedger;

public final class MilitaryDeathLedgerTest {
    private static int failures;
    private static void check(boolean condition, String label) {
        System.out.println((condition ? "ok   " : "FAIL ") + label);
        if (!condition) failures++;
    }

    public static void main(String[] args) {
        String character = "11111111-1111-4111-8111-111111111111";
        String entity = "22222222-2222-4222-8222-222222222222";
        MilitaryDeathLedger ledger = new MilitaryDeathLedger();
        MilitaryDeathLedger.Pending death = new MilitaryDeathLedger.Pending(character, entity, 412, "combat");

        check(ledger.stage(death) && ledger.size() == 1,
                "death is staged once before any Character lifecycle mutation");
        check(!ledger.stage(death) && ledger.size() == 1,
                "retrying the exact same death record is idempotent");
        boolean collision = false;
        try {
            ledger.stage(new MilitaryDeathLedger.Pending(character, entity, 413, "disease"));
        } catch (IllegalStateException expected) { collision = true; }
        check(collision && ledger.size() == 1,
                "reusing a Character death key with a different cause/year fails closed");

        MilitaryDeathLedger restored = new MilitaryDeathLedger();
        restored.fromJson(ledger.toJson());
        check(restored.get(character).equals(death) && restored.size() == 1,
                "pending death outbox survives save/load exactly");
        check(restored.complete(character) && !restored.complete(character) && restored.size() == 0,
                "death completion marker can be acknowledged exactly once");

        boolean duplicateEntity = false;
        try {
            ledger.stage(new MilitaryDeathLedger.Pending(
                    "33333333-3333-4333-8333-333333333333", entity, 412, "combat"));
        } catch (IllegalStateException expected) { duplicateEntity = true; }
        check(duplicateEntity, "one physical troop body cannot own two pending death records");

        boolean corrupt = false;
        try {
            restored.fromJson("{\"format\":\"wf-military-death-outbox/1\",\"pending\":[{\"character\":\"bad\",\"entity\":\""
                    + entity + "\",\"year\":412,\"cause\":\"combat\"}]}");
        } catch (IllegalArgumentException expected) { corrupt = true; }
        check(corrupt, "malformed pending identity fails closed during recovery");

        if (failures > 0) throw new AssertionError(failures + " MilitaryDeathLedger tests failed");
        System.out.println("MilitaryDeathLedger: ALL OK");
    }
}
