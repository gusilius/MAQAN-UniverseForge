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
        String otherCharacter = "33333333-3333-4333-8333-333333333333";
        String entity = "22222222-2222-4222-8222-222222222222";
        String nextEntity = "44444444-4444-4444-8444-444444444444";
        MilitaryTransitionLedger ledger = new MilitaryTransitionLedger();
        MilitaryTransitionLedger.Pending desertion = new MilitaryTransitionLedger.Pending(
                character, entity, MilitaryTransitionLedger.Kind.DESERTION, 412, "north_colony");

        check(ledger.stage(desertion) && ledger.size() == 1,
                "desertion intent is staged before removing the physical soldier");
        check(!ledger.stage(desertion) && ledger.size() == 1,
                "replaying the exact same transition is idempotent");
        boolean collision = false;
        try {
            ledger.stage(new MilitaryTransitionLedger.Pending(character, entity,
                    MilitaryTransitionLedger.Kind.DEMOBILIZATION, 413, "south_colony"));
        } catch (IllegalStateException expected) { collision = true; }
        check(collision && ledger.size() == 1,
                "reusing a troop entity ID with a different payload fails closed");

        MilitaryTransitionLedger restored = new MilitaryTransitionLedger();
        restored.fromJson(ledger.toJson());
        check(desertion.equals(restored.get(character)),
                "pending transition survives serialization and restart exactly");
        check(restored.complete(character) && !restored.complete(character) && restored.size() == 0,
                "completion moves the transition to a terminal receipt");
        check(restored.get(character) == null && restored.isCompleted(character, entity),
                "completed transition is no longer pending but still blocks its exact stale body");
        check(restored.hasCompletedEntity(entity),
                "terminal receipt blocks stale entity NBT even when its CharacterId field is missing");
        check(!restored.isCompleted(otherCharacter, entity),
                "a terminal receipt cannot remove a different Character's entity");

        MilitaryTransitionLedger afterRestart = new MilitaryTransitionLedger();
        afterRestart.fromJson(restored.toJson());
        check(afterRestart.isCompleted(character, entity) && afterRestart.pending().isEmpty(),
                "terminal receipt survives serialization and restart");
        check(!afterRestart.stage(desertion) && afterRestart.size() == 0,
                "replayed completed transition cannot become pending again");

        MilitaryTransitionLedger.Pending reenlistment = new MilitaryTransitionLedger.Pending(
                character, nextEntity, MilitaryTransitionLedger.Kind.DEMOBILIZATION, 420, "north_colony");
        check(afterRestart.stage(reenlistment) && afterRestart.size() == 1
                        && afterRestart.isCompleted(character, entity),
                "the same Character can re-enlist with a new physical entity while the old tombstone remains");
        check(afterRestart.complete(character)
                        && afterRestart.isCompleted(character, nextEntity)
                        && afterRestart.isCompleted(character, entity),
                "each physical service interval retains its own terminal receipt");

        MilitaryTransitionLedger reopened = new MilitaryTransitionLedger();
        reopened.fromJson(ledger.toJson());
        check(reopened.complete(character) && reopened.reopen(character, entity)
                        && reopened.get(character).equals(desertion)
                        && !reopened.isCompleted(character, entity),
                "failed terminal persistence can safely reopen the in-memory transition");

        boolean duplicateEntity = false;
        try {
            ledger.stage(new MilitaryTransitionLedger.Pending(
                    otherCharacter, entity, MilitaryTransitionLedger.Kind.DESERTION, 412, ""));
        } catch (IllegalStateException expected) { duplicateEntity = true; }
        check(duplicateEntity, "one physical troop cannot own two transition receipts");

        boolean duplicateActiveCharacter = false;
        try {
            ledger.stage(new MilitaryTransitionLedger.Pending(
                    character, nextEntity, MilitaryTransitionLedger.Kind.DESERTION, 412, ""));
        } catch (IllegalStateException expected) { duplicateActiveCharacter = true; }
        check(duplicateActiveCharacter, "one Character cannot have two active military transitions");

        MilitaryTransitionLedger legacy = new MilitaryTransitionLedger();
        legacy.fromJson("{\"format\":\"wf-military-transition-outbox/1\",\"pending\":[{"
                + "\"character\":\"" + character + "\",\"entity\":\"" + entity
                + "\",\"kind\":\"DESERTION\",\"year\":412,\"colony\":\"\"}]}");
        check(legacy.get(character) != null && legacy.toJson().contains("wf-military-transition-outbox/2"),
                "legacy v1 pending-only saves migrate to v2");

        boolean malformed = false;
        try {
            legacy.fromJson("{\"format\":\"wf-military-transition-outbox/2\",\"pending\":[{"
                    + "\"character\":\"bad\",\"entity\":\"" + entity
                    + "\",\"kind\":\"DESERTION\",\"year\":412,\"colony\":\"\"}],\"completed\":[]}");
        } catch (IllegalArgumentException expected) { malformed = true; }
        check(malformed, "corrupt identity fails closed during transition recovery");

        MilitaryTransitionLedger.Pending returned = new MilitaryTransitionLedger.Pending(
                "66666666-6666-4666-8666-666666666666",
                "77777777-7777-4777-8777-777777777777",
                MilitaryTransitionLedger.Kind.STRATEGIC_RETURN, 420,
                "army:22222222-2222-4222-8222-222222222222:north-map:7");
        check(legacy.stage(returned) && legacy.complete(returned.characterGlobalId())
                        && legacy.isCompleted(returned.characterGlobalId(), returned.troopEntityId()),
                "strategic return keeps a terminal tombstone for a late-loaded troop chunk");

        if (failures > 0) throw new AssertionError(failures + " MilitaryTransitionLedger tests failed");
        System.out.println("MilitaryTransitionLedger: ALL OK");
    }
}
