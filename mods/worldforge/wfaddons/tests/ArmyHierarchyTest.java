package dev.worldforge.tests;

import dev.worldforge.mod.identity.WorldId;
import dev.worldforge.troops.ArmyHierarchy;
import dev.worldforge.troops.ArmyRef;
import dev.worldforge.troops.Squads;

import java.util.List;

public final class ArmyHierarchyTest {
    private static int failures;
    private static void check(boolean value, String message) {
        if (!value) { failures++; System.out.println("FAIL " + message); }
        else System.out.println("ok   " + message);
    }

    public static void main(String[] args) {
        WorldId world = WorldId.parse("11111111-1111-1111-1111-111111111111");
        ArmyRef northRef = new ArmyRef(world, "north-map", 42);
        ArmyRef southRef = new ArmyRef(world, "south-map", 42);
        WorldId otherWorld = WorldId.parse("22222222-2222-2222-2222-222222222222");
        ArmyRef otherWorldRef = new ArmyRef(otherWorld, "north-map", 42);
        Squads squads = new Squads();
        Squads.Squad first = squads.create("player", "Авангард", List.of("unit-1", "unit-2"));
        Squads.Squad second = squads.create("player", "Резерв", List.of("unit-3"));
        ArmyHierarchy hierarchy = new ArmyHierarchy();
        hierarchy.ensureArmy(northRef, "Первая армия", "player", "realm-north");
        hierarchy.ensureArmy(southRef, "Вторая армия", "player", "realm-south");
        hierarchy.ensureArmy(otherWorldRef, "Армия другого мира", "player", "realm-north");
        ArmyHierarchy.Corps vanguard = hierarchy.defaultCorps(northRef);
        ArmyHierarchy.Corps reserve = hierarchy.createCorps(southRef, "Резервный корпус");
        check(hierarchy.army(northRef) != hierarchy.army(southRef)
                        && hierarchy.army(otherWorldRef) != null && hierarchy.armies().size() == 3,
                "same map-local army ID remains independent across maps and WorldIds");
        check(hierarchy.assignSquad(vanguard.id, first.id, squads)
                        && hierarchy.assignSquad(reserve.id, second.id, squads),
                "army contains corps and corps contain canonical RTS squads");
        check(hierarchy.appointArmyCommander(northRef, "c17")
                        && hierarchy.appointCorpsCommander(vanguard.id, "c18"),
                "army and corps commanders use canonical Character IDs");
        int issued = hierarchy.commandArmy(northRef, squads,
                new Squads.Order(Squads.Kind.MOVE, 120, -40, ""), false);
        check(issued == 1 && first.current() != null && first.current().kind() == Squads.Kind.MOVE
                        && second.current() == null,
                "army-level orders never leak into another map with the same numeric army ID");
        hierarchy.commandCorps(reserve.id, squads,
                new Squads.Order(Squads.Kind.HOLD, 0, 0, ""), false);
        check(second.current().kind() == Squads.Kind.HOLD && first.current().kind() == Squads.Kind.MOVE,
                "corps orders affect only their own subordinate squads");

        ArmyHierarchy restored = new ArmyHierarchy();
        restored.fromJson(hierarchy.toJson(), world, ignored -> null);
        check(restored.army(northRef) != null && restored.army(southRef) != null
                        && restored.army(otherWorldRef) != null
                        && restored.corps(vanguard.id) != null
                        && restored.corps(reserve.id).squadIds.contains(second.id)
                        && restored.corpsForSquad(second.id).id.equals(reserve.id),
                "world/map-scoped army hierarchy and custom squad membership survive save/reload");
        check(restored.army(42) == null,
                "legacy numeric army lookup fails closed when map-local IDs are ambiguous");

        String legacy = "{\"format\":\"wf-army-hierarchy/1\",\"nextCorps\":2,"
                + "\"armies\":[{\"id\":7,\"name\":\"Legacy\",\"owner\":\"player\",\"realm\":\"realm-old\",\"commander\":\"\",\"corps\":[]}],"
                + "\"corps\":[{\"id\":\"corps-1\",\"army\":7,\"name\":\"1-й корпус\",\"commander\":\"\",\"squads\":[]}]}";
        ArmyHierarchy migrated = new ArmyHierarchy();
        migrated.fromJson(legacy, world, old -> "old-map");
        check(migrated.army(new ArmyRef(world, "old-map", 7)) != null,
                "legacy numeric hierarchy migrates through an explicit map resolver");
        boolean rejectedAmbiguous = false;
        try { new ArmyHierarchy().fromJson(legacy, world, old -> null); }
        catch (IllegalStateException expected) { rejectedAmbiguous = true; }
        check(rejectedAmbiguous, "ambiguous legacy hierarchy migration is rejected instead of guessed");

        Squads foreignSquads = new Squads();
        Squads.Squad foreignSquad = foreignSquads.create("another-player", "Чужой отряд", List.of("foreign-unit"));
        check(!restored.assignSquad(reserve.id, foreignSquad.id, foreignSquads)
                        && !restored.appointArmyCommander(new ArmyRef(world, "missing", 999), "c1"),
                "foreign player squads and missing army references are rejected");
        squads.remove("unit-3");
        restored.pruneSquads(squads);
        check(restored.corps(reserve.id).squadIds.isEmpty(),
                "destroyed/disbanded tactical squads are pruned from the strategic hierarchy");
        if (failures > 0) throw new AssertionError(failures + " army hierarchy tests failed");
    }
}
