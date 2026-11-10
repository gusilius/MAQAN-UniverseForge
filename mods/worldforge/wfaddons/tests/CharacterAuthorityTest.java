package dev.worldforge.tests;

import dev.worldforge.mod.identity.WorldId;
import dev.worldforge.people.Character;
import dev.worldforge.people.CharacterAuthority;
import dev.worldforge.people.CharacterIdentity;
import dev.worldforge.people.CharacterRegistry;

import java.util.UUID;

/** Global character authority collision and legacy-lookup tests. */
public final class CharacterAuthorityTest {
    private static int failures;
    private static void check(boolean value, String label) {
        System.out.println((value ? "ok   " : "FAIL ") + label);
        if (!value) failures++;
    }

    public static void main(String[] args) {
        CharacterAuthority authority = new CharacterAuthority();
        WorldId world = new WorldId(UUID.fromString("11111111-1111-4111-8111-111111111111"));
        authority.beginWorld(world);
        CharacterRegistry north = new CharacterRegistry();
        CharacterRegistry south = new CharacterRegistry();
        Character a = north.create("Aldric", "", "", "m", 0);
        Character b = south.create("Beatrice", "", "", "f", 0);
        check(a.id.equals("c1") && b.id.equals("c1"), "legacy per-map IDs remain unchanged during migration");
        check(!a.globalId.equals(b.globalId), "each new Character receives a unique immutable global UUID");
        authority.registerMap("north", north);
        authority.registerMap("south", south);
        check(authority.size() == 2 && authority.mapCount() == 2, "global authority indexes both map partitions");
        CharacterIdentity aIdentity = new CharacterIdentity(world, "north", "c1", a.globalId);
        CharacterIdentity bIdentity = new CharacterIdentity(world, "south", "c1", b.globalId);
        check(authority.get(aIdentity) == a && authority.get(bIdentity) == b,
                "qualified identity resolves colliding legacy IDs");
        check(authority.getGlobal(a.globalId) == a && authority.registryForGlobal(b.globalId) == south,
                "global UUID resolves person and owning map registry");
        check(authority.getLegacy("c1") == null, "ambiguous bare legacy ID fails closed");
        boolean duplicateRejected = false;
        try {
            CharacterRegistry duplicate = new CharacterRegistry();
            Character clone = duplicate.create("Clone", "", "", "m", 0);
            // Build a duplicate global UUID through a persisted snapshot and verify authority rejects it.
            String json = north.toJson().replace(a.globalId, clone.globalId);
            duplicate.fromJson(json, "duplicate-test");
            authority.registerMap("duplicate", duplicate);
        } catch (RuntimeException expected) { duplicateRejected = true; }
        check(duplicateRejected, "duplicate global UUID across maps is rejected");
        authority.clear();
        check(authority.size() == 0 && authority.worldId() == null, "world shutdown clears authority index");
        if (failures > 0) throw new AssertionError(failures + " CharacterAuthority tests failed");
        System.out.println("CharacterAuthority: ALL OK");
    }
}
