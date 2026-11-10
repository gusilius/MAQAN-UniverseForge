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
        authority.registerMap("north", north);
        authority.registerMap("south", south);
        check(authority.size() == 2 && authority.mapCount() == 2, "global authority indexes both map partitions");
        check(authority.get(new CharacterIdentity(world, "north", "c1")) == a
                && authority.get(new CharacterIdentity(world, "south", "c1")) == b,
                "qualified CharacterIdentity resolves colliding legacy IDs without ambiguity");
        check(authority.getLegacy("c1") == null, "ambiguous bare legacy ID fails closed");
        authority.clear();
        check(authority.size() == 0 && authority.worldId() == null, "world shutdown clears authority index");
        if (failures > 0) throw new AssertionError(failures + " CharacterAuthority tests failed");
        System.out.println("CharacterAuthority: ALL OK");
    }
}
