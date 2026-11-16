package dev.worldforge.tests;

import dev.worldforge.troops.ArmyHierarchy;
import dev.worldforge.troops.Squads;
import dev.worldforge.troops.CommandDelegation;

import java.util.List;

public final class ArmyHierarchyTest {
    private static int failures;
    private static void check(boolean value, String message) {
        if (!value) { failures++; System.out.println("FAIL " + message); }
        else System.out.println("ok   " + message);
    }

    public static void main(String[] args) {
        Squads squads = new Squads();
        Squads.Squad first = squads.create("player", "Авангард", List.of("unit-1", "unit-2"));
        Squads.Squad second = squads.create("player", "Резерв", List.of("unit-3"));
        ArmyHierarchy hierarchy = new ArmyHierarchy();
        hierarchy.ensureArmy(42, "Первая армия", "player", "realm-north");
        ArmyHierarchy.Corps vanguard = hierarchy.defaultCorps(42);
        ArmyHierarchy.Corps reserve = hierarchy.createCorps(42, "Резервный корпус");
        check(hierarchy.assignSquad(vanguard.id, first.id, squads)
                        && hierarchy.assignSquad(reserve.id, second.id, squads),
                "army contains corps and corps contain canonical RTS squads");
        check(hierarchy.corpsForSquad(first.id) == vanguard
                        && hierarchy.corpsForSquad(second.id) == reserve,
                "reverse command index resolves squad parent without scanning every corps");
        check(hierarchy.assignSquad(reserve.id, first.id, squads)
                        && hierarchy.corpsForSquad(first.id) == reserve
                        && !vanguard.squadIds.contains(first.id),
                "reassignment updates both forward and reverse corps membership");
        hierarchy.assignSquad(vanguard.id, first.id, squads);
        hierarchy.unassignSquad(first.id);
        check(hierarchy.corpsForSquad(first.id) == null && !vanguard.squadIds.contains(first.id),
                "unassign clears indexed and persisted squad membership");
        hierarchy.assignSquad(vanguard.id, first.id, squads);
        check(hierarchy.appointArmyCommander(42, "c17")
                        && hierarchy.appointCorpsCommander(vanguard.id, "c18"),
                "army and corps commanders use canonical Character IDs");
        int issued = hierarchy.commandArmy(42, squads,
                new Squads.Order(Squads.Kind.MOVE, 120, -40, ""), false);
        check(issued == 2 && first.current().kind() == Squads.Kind.MOVE
                        && second.current().kind() == Squads.Kind.MOVE,
                "army-level orders fan out to every corps and subordinate squad");
        hierarchy.commandCorps(reserve.id, squads,
                new Squads.Order(Squads.Kind.HOLD, 0, 0, ""), false);
        check(second.current().kind() == Squads.Kind.HOLD && first.current().kind() == Squads.Kind.MOVE,
                "corps orders affect only their own subordinate squads");

        ArmyHierarchy restored = new ArmyHierarchy();
        restored.fromJson(hierarchy.toJson());
        check(restored.army(42) != null && restored.corps(vanguard.id) != null
                        && restored.corps(reserve.id).squadIds.contains(second.id)
                        && restored.corpsForSquad(second.id).id.equals(reserve.id),
                "army/corps hierarchy and custom squad membership survive save/reload");
        check(restored.corpsForSquad(first.id) == restored.corps(vanguard.id),
                "save/load rebuilds the reverse squad-to-corps index");
        Squads foreignSquads = new Squads();
        Squads.Squad foreignSquad = foreignSquads.create("another-player", "Чужой отряд", List.of("foreign-unit"));
        check(!restored.assignSquad(reserve.id, foreignSquad.id, foreignSquads)
                        && !restored.appointArmyCommander(999, "c1"),
                "foreign player squads and missing army references are rejected");
        squads.remove("unit-3");
        restored.pruneSquads(squads);
        check(restored.corps(reserve.id).squadIds.isEmpty(),
                "destroyed/disbanded tactical squads are pruned from the strategic hierarchy");
        Squads indexed = new Squads();
        Squads.Squad indexedA = indexed.create("player", "A", List.of("a1", "a2", "a3"));
        check(indexed.squadOf("a2") == indexedA,
                "reverse membership index resolves a soldier to its squad");
        Squads.Squad split = indexed.split(indexedA, List.of("a2"));
        check(split != null && indexed.squadOf("a2") == split
                        && indexed.squadOf("a1") == indexedA,
                "split moves membership index entries without changing character identity");
        check(indexed.merge(indexedA.id, split.id) && indexed.squadOf("a2") == indexedA,
                "merge reassigns reverse membership entries to the receiving squad");
        indexed.disband(indexedA.id);
        check(indexed.squadOf("a1") == null && indexed.squadOf("a2") == null,
                "disband removes all reverse membership entries");
        Squads compatibility = new Squads();
        Squads.Squad legacy = compatibility.create("player", "Legacy", List.of("legacy-1"));
        legacy.members.add("legacy-2"); // Compatibility with older addons that still mutate the public list.
        check(compatibility.squadOf("legacy-2") == legacy,
                "reverse index repairs legacy direct membership mutations");
        Squads restoredIndex = new Squads();
        restoredIndex.fromJson(compatibility.toJson());
        check(restoredIndex.squadOf("legacy-1") != null,
                "save/load rebuilds the reverse membership index");
        ArmyHierarchy legacyHierarchy = new ArmyHierarchy();
        legacyHierarchy.ensureArmy(7, "Legacy", "player", "");
        ArmyHierarchy.Corps legacyCorps = legacyHierarchy.defaultCorps(7);
        legacyCorps.squadIds.add(legacy.id);
        check(legacyHierarchy.corpsForSquad(legacy.id) == legacyCorps,
                "reverse corps index repairs legacy direct squad-list mutations");
        CommandDelegation delegation = new CommandDelegation();
        check(delegation.assign(first.id, CommandDelegation.Mode.DELEGATED, "commander-18",
                        "hold-the-river", 48, true, false, false),
                "delegation requires a canonical commander and stores an explicit operational leash");
        check(!delegation.acceptsInheritedOrder(first.id)
                        && delegation.acceptsInheritedOrder(second.id),
                "army/corps orders pass through only to player-direct squads");
        check(delegation.mayIssue(first.id, "commander-18", CommandDelegation.Action.ENGAGE_VISIBLE_THREAT)
                        && !delegation.mayIssue(first.id, "commander-18", CommandDelegation.Action.PURSUE)
                        && !delegation.mayIssue(first.id, "commander-18", CommandDelegation.Action.RETREAT)
                        && !delegation.mayIssue(first.id, "intruder", CommandDelegation.Action.REPOSITION),
                "delegated commander authority enforces engagement, pursuit, retreat and identity permissions");
        check(!delegation.assign(second.id, CommandDelegation.Mode.DELEGATED, "commander-19", "", 20,
                        true, true, true),
                "delegated command requires a concrete objective rather than an empty standing order");
        check(!delegation.assign(second.id, CommandDelegation.Mode.AUTONOMOUS, "", "advance", 20,
                        true, true, true),
                "autonomous command cannot be enabled without an appointed Character commander");
        String delegationSave = delegation.toJson();
        CommandDelegation restoredDelegation = new CommandDelegation();
        restoredDelegation.fromJson(delegationSave);
        check(!restoredDelegation.acceptsInheritedOrder(first.id)
                        && restoredDelegation.get(first.id).objective().equals("hold-the-river"),
                "delegated command policy and objective survive save/reload");
        check(restoredDelegation.reclaim(first.id) && restoredDelegation.acceptsInheritedOrder(first.id)
                        && !restoredDelegation.reclaim(first.id),
                "player reclaim is immediate, idempotent and restores inherited direct orders");
        restoredDelegation.assign(second.id, CommandDelegation.Mode.AUTONOMOUS, "commander-22",
                "secure-crossing", 64, true, true, false);
        ArmyHierarchy.DispatchStats dispatch = restored.commandArmy(42, squads,
                new Squads.Order(Squads.Kind.MOVE, 300, 200, ""), false, restoredDelegation);
        check(dispatch.issued() == 1 && dispatch.delegated() == 1
                        && first.current().kind() == Squads.Kind.MOVE
                        && second.current().kind() == Squads.Kind.HOLD,
                "army fan-out counts and skips delegated squads without overwriting their local orders");
        squads.disband(second.id);
        restoredDelegation.prune(squads);
        check(restoredDelegation.get(second.id).mode() == CommandDelegation.Mode.PLAYER_DIRECT,
                "disbanded squad delegation policy is pruned");
        CommandDelegation invalidDelegation = new CommandDelegation();
        invalidDelegation.fromJson("{\"format\":\"wf-command-delegation/1\",\"policies\":["
                + "{\"squad\":\"bad\",\"mode\":\"DELEGATED\",\"commander\":\"\","
                + "\"objective\":\"\",\"leash\":-1,\"engage\":true}]}");
        check(invalidDelegation.all().isEmpty(),
                "invalid persisted command policies are rejected instead of granting authority");

        restoredIndex.fromJson("{broken");
        check(restoredIndex.squadOf("legacy-1") == null,
                "invalid persistence input clears stale reverse membership entries");
        if (failures > 0) throw new AssertionError(failures + " army hierarchy tests failed");
    }
}
