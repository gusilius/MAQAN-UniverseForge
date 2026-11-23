package dev.worldforge.tests;

import dev.worldforge.people.Character;
import dev.worldforge.people.CharacterRegistry;
import dev.worldforge.people.Role;
import dev.worldforge.people.mc.NpcSpawnTargetLookup;
import java.util.List;
import java.util.Map;

public final class NpcSpawnTargetLookupTest {
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    public static void main(String[] args) {
        CharacterRegistry registry = new CharacterRegistry();
        Character ruler = registry.create("Ruler", "", "north", "m", 250);
        Character steward = registry.create("Steward", "", "north", "f", 260);
        Character dead = registry.create("Dead", "", "north", "m", 230);
        Character distant = registry.create("Distant", "", "north", "m", 240);
        registry.appoint(ruler.id, "king", "realm:r1");
        registry.appoint(steward.id, "steward", "title:t1");
        registry.appoint(dead.id, "king", "realm:r1");
        registry.appoint(distant.id, "king", "realm:other");
        dead.alive = false;
        Role king = Role.parse("{\"id\":\"king\",\"name\":\"King\",\"per\":\"realm\",\"count\":1,\"dialogue\":\"\",\"noble\":true,\"family\":false,\"unlocks\":[]}");
        Role stewardRole = Role.parse("{\"id\":\"steward\",\"name\":\"Steward\",\"per\":\"barony\",\"count\":1,\"dialogue\":\"\",\"noble\":false,\"family\":false,\"unlocks\":[]}");
        Map<NpcSpawnTargetLookup.RolePlace, NpcSpawnTargetLookup.Location> eligible = Map.of(
            new NpcSpawnTargetLookup.RolePlace("king", "realm:r1"), new NpcSpawnTargetLookup.Location(king, 100, 200),
            new NpcSpawnTargetLookup.RolePlace("steward", "title:t1"), new NpcSpawnTargetLookup.Location(stewardRole, 300, 400));
        List<NpcSpawnTargetLookup.Candidate> found = NpcSpawnTargetLookup.collect(registry, eligible);
        check(found.size() == 2, "only living holders at eligible locations are returned");
        check(found.get(0).character() == ruler && found.get(0).role() == king
            && found.get(0).x() == 100 && found.get(0).z() == 200, "realm anchor is preserved");
        check(found.get(1).character() == steward && found.get(1).role() == stewardRole,
            "title anchor is preserved");
        check(NpcSpawnTargetLookup.collect(registry, Map.of()).isEmpty(), "empty local anchors return no candidates");
        System.out.println("NpcSpawnTargetLookupTest: OK");
    }
}
