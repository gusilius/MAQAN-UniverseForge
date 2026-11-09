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
        check(!restored.assignSquad(reserve.id, first.id, new Squads())
                        && !restored.appointArmyCommander(999, "c1"),
                "invalid army ownership and missing army references are rejected");
        if (failures > 0) throw new AssertionError(failures + " army hierarchy tests failed");
    }
}
