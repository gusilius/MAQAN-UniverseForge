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
        check(authority.bindEntity(a.globalId, "npc", "shared-physical-entity"),
                "global authority binds a body to its canonical Character");
        check(!authority.bindEntity(b.globalId, "troop", "shared-physical-entity"),
                "one physical entity UUID cannot be attached to two Characters across map partitions");
        check(authority.bindEntity(b.globalId, "troop", "another-physical-entity"),
                "a distinct physical body can bind to a different map-owned Character");
        Character late = north.create("Late arrival", "", "", "m", 1);
        check(authority.getGlobal(late.globalId) == late,
                "characters created after map registration are indexed without a second registration");

        CharacterRegistry reload = new CharacterRegistry();
        Character beforeReload = reload.create("Before reload", "", "", "m", 1);
        authority.registerMap("reload", reload);
        CharacterRegistry replacement = new CharacterRegistry();
        Character afterReload = replacement.create("After reload", "", "", "m", 2);
        reload.fromJson(replacement.toJson(), world + "|reload");
        check(authority.getGlobal(beforeReload.globalId) == null
                        && authority.getGlobal(afterReload.globalId) == reload.get(afterReload.id),
                "global identity index refreshes after a same-size registry replacement");

        CharacterRegistry players = new CharacterRegistry();
        Character player = players.create("PlayerOne", "", "human", "m", 80);
        player.props.put("genesis.player_uuid", "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
        player.props.put("genesis.initialized", true);
        String playerSave = players.toJson();
        CharacterRegistry restoredPlayers = new CharacterRegistry();
        restoredPlayers.fromJson(playerSave, world + "|__players__");
        authority.registerMap("__players__", restoredPlayers);
        Character restoredPlayer = restoredPlayers.all().iterator().next();
        check(restoredPlayer.globalId.equals(player.globalId)
                        && "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa".equals(restoredPlayer.props.get("genesis.player_uuid"))
                        && authority.getGlobal(player.globalId) == restoredPlayer,
                "Genesis player identity and player UUID survive registry restart without duplication");

        CharacterRegistry duplicate = new CharacterRegistry();
        Character clone = duplicate.create("Clone", "", "", "m", 0);
        String duplicateSave = duplicate.toJson().replace(clone.globalId, a.globalId);
        duplicate.fromJson(duplicateSave, world + "|duplicate");
        boolean duplicateRejected = false;
        try {
            authority.registerMap("duplicate", duplicate);
        } catch (IllegalStateException expected) {
            duplicateRejected = expected.getMessage().contains("Global Character UUID collision");
        }
        check(duplicateRejected, "registration rejects a real duplicate global UUID from another map");
        check(authority.getGlobal(a.globalId) == a
                        && authority.getGlobal(late.globalId) == late
                        && authority.size() == 5
                        && authority.mapCount() == 4,
                "failed map registration preserves every previously indexed Character and registry");
        authority.beginWorld(world);
        check(authority.getGlobal(a.globalId) == a && authority.size() == 5 && authority.mapCount() == 4,
                "re-entering the same WorldId preserves already registered Character partitions");

        authority.clear();
        check(authority.size() == 0 && authority.worldId() == null, "world shutdown clears authority index");
        if (failures > 0) throw new AssertionError(failures + " CharacterAuthority tests failed");
        System.out.println("CharacterAuthority: ALL OK");
    }
}
