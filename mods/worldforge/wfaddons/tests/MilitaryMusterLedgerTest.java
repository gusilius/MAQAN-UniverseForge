package dev.worldforge.tests;

import dev.worldforge.troops.MilitaryMusterLedger;

import java.util.List;
import java.util.Map;

public final class MilitaryMusterLedgerTest {
    private static int failures;
    private static void check(boolean value, String label) {
        System.out.println((value ? "ok   " : "FAIL ") + label);
        if (!value) failures++;
    }

    public static void main(String[] args) {
        String operationId = "11111111-1111-4111-8111-111111111111";
        String worldId = "22222222-2222-4222-8222-222222222222";
        String entity = "33333333-3333-4333-8333-333333333333";
        String character = "44444444-4444-4444-8444-444444444444";
        MilitaryMusterLedger.Spawn spawn = new MilitaryMusterLedger.Spawn(
                entity, "footman", "muster-squad-1", "minecraft:overworld",
                12.5, 64, -3.5, 90, "");
        MilitaryMusterLedger.SquadPlan squad = new MilitaryMusterLedger.SquadPlan(
                "muster-squad-1", "1-й корпус", "owner", "north",
                12.5, -3.5, List.of(entity));
        MilitaryMusterLedger.StockDebit stock = new MilitaryMusterLedger.StockDebit(
                "north_colony", "military-muster-stock:receipt-1", Map.of("food", 10, "iron", 2));
        MilitaryMusterLedger.Operation operation = new MilitaryMusterLedger.Operation(
                operationId, worldId, "north-map", 7, "north", "owner",
                Map.of("levy", -10), List.of(stock), List.of(squad), List.of(spawn),
                MilitaryMusterLedger.Phase.PREPARED);

        MilitaryMusterLedger ledger = new MilitaryMusterLedger();
        check(ledger.stage(operation) && ledger.pending().size() == 1,
                "muster intent is staged before any strategic or settlement mutation");
        check(!ledger.stage(operation) && ledger.pending().size() == 1,
                "replaying an identical operation ID is idempotent");

        boolean payloadCollision = false;
        try {
            ledger.stage(new MilitaryMusterLedger.Operation(operationId, worldId, "north-map", 7, "north", "owner",
                    Map.of("levy", -20), List.of(stock), List.of(squad), List.of(spawn),
                    MilitaryMusterLedger.Phase.PREPARED));
        } catch (IllegalStateException expected) { payloadCollision = true; }
        check(payloadCollision, "reusing a muster operation ID with a different manpower payload fails closed");

        check(!ledger.advance(operationId, MilitaryMusterLedger.Phase.PREPARED,
                        MilitaryMusterLedger.Phase.STOCK_DEBITED),
                "transaction phases cannot be skipped");
        check(ledger.advance(operationId, MilitaryMusterLedger.Phase.PREPARED,
                        MilitaryMusterLedger.Phase.ROSTER_DEBITED),
                "strategic roster debit advances only after its durable checkpoint");

        MilitaryMusterLedger reloaded = new MilitaryMusterLedger();
        reloaded.fromJson(ledger.toJson());
        check(reloaded.get(operationId).phase() == MilitaryMusterLedger.Phase.ROSTER_DEBITED,
                "transaction phase survives save/reload");
        check(reloaded.recordCharacter(operationId, entity, character),
                "physical troop can persist its canonical Character identity");
        check(!reloaded.recordCharacter(operationId, entity,
                        "55555555-5555-4555-8555-555555555555"),
                "a troop Character identity cannot silently change on replay");

        for (MilitaryMusterLedger.Phase phase : List.of(
                MilitaryMusterLedger.Phase.STOCK_DEBITED,
                MilitaryMusterLedger.Phase.SQUADS_SAVED,
                MilitaryMusterLedger.Phase.ENTITIES_SPAWNED,
                MilitaryMusterLedger.Phase.COMMITTED)) {
            MilitaryMusterLedger.Phase current = reloaded.get(operationId).phase();
            check(reloaded.advance(operationId, current, phase),
                    "transaction advances to " + phase);
        }
        check(reloaded.pending().isEmpty() && reloaded.operations().size() == 1,
                "committed operation remains as a durable idempotency receipt");

        MilitaryMusterLedger afterRestart = new MilitaryMusterLedger();
        afterRestart.fromJson(reloaded.toJson());
        check(afterRestart.get(operationId).phase() == MilitaryMusterLedger.Phase.COMMITTED
                        && afterRestart.findSpawn(entity).spawn().characterGlobalId().equals(character),
                "committed muster and Character link survive another restart");
        check(afterRestart.findSpawn(entity).operation().armyId() == 7,
                "physical UUID resolves back to its exact map-scoped strategic army");

        boolean malformedRejected = false;
        try {
            afterRestart.fromJson("{\"format\":\"wf-military-muster/1\",\"operations\":[{}]}");
        } catch (IllegalArgumentException expected) { malformedRejected = true; }
        check(malformedRejected, "corrupt transaction payload fails closed");

        if (failures > 0) throw new AssertionError(failures + " MilitaryMusterLedger tests failed");
        System.out.println("MilitaryMusterLedger: ALL OK");
    }
}
