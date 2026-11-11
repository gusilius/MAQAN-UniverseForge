package dev.worldforge.tests;

import dev.worldforge.troops.MilitaryTransitionLedger;

public final class MilitaryTransitionLedgerTest {
    private static int failures;
    private static void check(boolean value, String label) {
        System.out.println((value ? "ok   " : "FAIL ") + label);
        if (!value) failures++;
    }

    public static void main(String[] args) {
        String character = "11111111-1111-4111-8111-111111111111";
        String entity = "22222222-2222-4222-8222-222222222222";
        MilitaryTransitionLedger ledger = new MilitaryTransitionLedger();
        MilitaryTransitionLedger.Pending desertion = new MilitaryTransitionLedger.Pending(
                character, entity, MilitaryTransitionLedger.Kind.DESERTION, 412, "north_colony");

        check(ledger.stage(desertion) && ledger.size() == 1,
                "desertion intent is persisted before removing the physical soldier");
        check(!ledger.stage(desertion) && ledger.size() == 1,
                "replaying the exact same transition is idempotent");
        boolean collision = false;
        try {
            ledger.stage(new MilitaryTransitionLedger.Pending(character, entity,
                    MilitaryTransitionLedger.Kind.DEMOBILIZATION, 413, "south_colony"));
        } catch (IllegalStateException expected) { collision = true; }
        check(collision && ledger.size() == 1,
                "conflicting transition for one Character fails closed");

        MilitaryTransitionLedger restored = new MilitaryTransitionLedger();
        restored.fromJson(ledger.toJson());
        check(desertion.equals(restored.get(character)),
                "pending transition survives serialization and restart exactly");
        check(restored.complete(character) && !restored.complete(character) && restored.size() == 0,
                "transition acknowledgement is exactly-once");

        boolean duplicateEntity = false;
        try {
            ledger.stage(new MilitaryTransitionLedger.Pending(
                    "33333333-3333-4333-8333-333333333333", entity,
                    MilitaryTransitionLedger.Kind.DESERTION, 412, ""));
        } catch (IllegalStateException expected) { duplicateEntity = true; }
        check(duplicateEntity, "one physical troop cannot own two pending transitions");

        boolean malformed = false;
        try {
            restored.fromJson("{\"format\":\"wf-military-transition-outbox/1\",\"pending\":[{"
                    + "\"character\":\"bad\",\"entity\":\"" + entity
                    + "\",\"kind\":\"DESERTION\",\"year\":412,\"colony\":\"\"}]}");
        } catch (IllegalArgumentException expected) { malformed = true; }
        check(malformed, "corrupt identity fails closed during transition recovery");

        if (failures > 0) throw new AssertionError(failures + " MilitaryTransitionLedger tests failed");
        System.out.println("MilitaryTransitionLedger: ALL OK");
    }
}
