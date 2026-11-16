package dev.worldforge.tests;

import dev.worldforge.troops.ArmyHierarchy;
import dev.worldforge.troops.Squads;

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
        Squads foreignSquads = new Squads();
        Squads.Squad foreignSquad = foreignSquads.create("another-player", "Чужой отряд", List.of("foreign-unit"));
        check(!restored.assignSquad(reserve.id, foreignSquad.id, foreignSquads)
                        && !restored.appointArmyCommander(999, "c1"),
                "foreign player squads and missing army references are rejected");
        squads.remove("unit-3");
        restored.pruneSquads(squads);
        check(restored.corps(reserve.id).squadIds.isEmpty(),
                "destroyed/disbanded tactical squads are pruned from the strategic hierarchy");
        boolean crossRealmRebindRejected = false;
        try { hierarchy.ensureArmy(42, "Подмена", "player", "realm-south"); }
        catch (IllegalStateException expected) { crossRealmRebindRejected = true; }
        check(crossRealmRebindRejected && "realm-north".equals(hierarchy.army(42).realm),
                "strategic army ID cannot silently rebind to another realm");

        CommandDelegation delegation = new CommandDelegation();
        check(delegation.get(first.id).mode() == CommandDelegation.Mode.PLAYER_DIRECT,
                "unconfigured squads remain under direct player control");
        check(delegation.assign(first.id, CommandDelegation.Mode.DELEGATED, "c17",
                        "hold-the-river", 80, true, false, true),
                "player may explicitly delegate one squad to a canonical commander");
        check(delegation.mayIssue(first.id, "c17", CommandDelegation.Action.REFORM)
                        && delegation.mayIssue(first.id, "c17", CommandDelegation.Action.ENGAGE_VISIBLE_THREAT)
                        && !delegation.mayIssue(first.id, "c17", CommandDelegation.Action.PURSUE)
                        && delegation.mayIssue(first.id, "c17", CommandDelegation.Action.RETREAT)
                        && !delegation.mayIssue(first.id, "c17", CommandDelegation.Action.CHANGE_OBJECTIVE),
                "delegated commander obeys explicit engagement, pursuit, retreat and objective limits");
        check(!delegation.mayIssue(first.id, "c99", CommandDelegation.Action.REFORM)
                        && delegation.get(second.id).mode() == CommandDelegation.Mode.PLAYER_DIRECT,
                "wrong commander cannot issue AI orders and sibling squads remain player-controlled");
        check(delegation.assign(second.id, CommandDelegation.Mode.AUTONOMOUS, "c18",
                        "north-border", 160, true, true, false)
                        && delegation.mayIssue(second.id, "c18", CommandDelegation.Action.CHANGE_OBJECTIVE)
                        && !delegation.mayIssue(second.id, "c18", CommandDelegation.Action.RETREAT),
                "autonomous command remains bounded by its policy");
        check(delegation.reclaim(first.id)
                        && delegation.get(first.id).mode() == CommandDelegation.Mode.PLAYER_DIRECT
                        && !delegation.mayIssue(first.id, "c17", CommandDelegation.Action.REFORM),
                "player can instantly reclaim a delegated squad");
        check(!delegation.assign(first.id, CommandDelegation.Mode.DELEGATED, "", "", 0, true, true, true),
                "delegation without a commander is rejected");
        delegation.prune(List.of(second.id));
        check(delegation.all().size() == 1 && delegation.get(first.id).mode() == CommandDelegation.Mode.PLAYER_DIRECT,
                "delegation policies for disbanded squads are pruned");

        if (failures > 0) throw new AssertionError(failures + " army hierarchy tests failed");
    }
}
