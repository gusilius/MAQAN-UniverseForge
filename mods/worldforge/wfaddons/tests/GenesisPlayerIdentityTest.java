package dev.worldforge.tests;

import dev.worldforge.genesis.PlayerCharacterBinding;
import dev.worldforge.mod.identity.WorldId;
import dev.worldforge.people.Character;
import dev.worldforge.people.CharacterAuthority;
import dev.worldforge.people.CharacterRegistry;

import java.util.List;
import java.util.UUID;

/** Genesis player UUID/global Character UUID reconciliation and duplicate protection. */
public final class GenesisPlayerIdentityTest {
    private static int failures;
    private static void check(boolean value, String label) {
        System.out.println((value ? "ok   " : "FAIL ") + label);
        if (!value) failures++;
    }
    private static boolean rejects(Runnable action) {
        try { action.run(); return false; }
        catch (IllegalStateException expected) { return true; }
    }

    public static void main(String[] args) {
        CharacterAuthority authority = new CharacterAuthority();
        authority.beginWorld(new WorldId(UUID.fromString("22222222-2222-4222-8222-222222222222")));
        CharacterRegistry map = new CharacterRegistry();
        CharacterRegistry players = new CharacterRegistry();
        authority.registerMap("main", map);
        authority.registerMap("__players__", players);

        String playerUuid = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
        Character player = map.create("PlayerOne", "", "human", "m", 80);
        player.props.put("genesis.player_uuid", playerUuid);
        player.props.put("genesis.initialized", true);
        check(PlayerCharacterBinding.resolveExisting(playerUuid, "", authority, List.of(map), players) == player,
                "Genesis resolves the existing player Character in a map partition");
        check(PlayerCharacterBinding.resolveExisting(playerUuid, player.globalId, authority, List.of(map), players) == player,
                "player UUID and saved global Character UUID resolve to the same person");

        CharacterRegistry restoredPlayers = new CharacterRegistry();
        Character persistedPlayer = players.create("PersistentPlayer", "", "human", "f", 81);
        String persistentUuid = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb";
        persistedPlayer.props.put("genesis.player_uuid", persistentUuid);
        restoredPlayers.fromJson(players.toJson(), authority.worldId() + "|__players__");
        CharacterRegistry restoredMap = new CharacterRegistry();
        restoredMap.fromJson(map.toJson(), authority.worldId() + "|main");
        CharacterAuthority restoredAuthority = new CharacterAuthority();
        restoredAuthority.beginWorld(authority.worldId());
        restoredAuthority.registerMap("main", restoredMap);
        restoredAuthority.registerMap("__players__", restoredPlayers);
        Character restored = PlayerCharacterBinding.resolveExisting(persistentUuid, persistedPlayer.globalId,
                restoredAuthority, List.of(restoredMap), restoredPlayers);
        check(restored != null && restored.globalId.equals(persistedPlayer.globalId),
                "player registry save/reload preserves the same global Character UUID");

        Character duplicate = restoredMap.create("Duplicate", "", "human", "m", 50);
        duplicate.props.put("genesis.player_uuid", playerUuid);
        check(rejects(() -> PlayerCharacterBinding.resolveExisting(playerUuid, player.globalId,
                        restoredAuthority, List.of(restoredMap), restoredPlayers)),
                "duplicate player UUID across Characters is rejected");

        Character other = restoredPlayers.create("OtherPlayer", "", "human", "m", 70);
        other.props.put("genesis.player_uuid", "cccccccc-cccc-4ccc-8ccc-cccccccccccc");
        check(rejects(() -> PlayerCharacterBinding.resolveExisting(persistentUuid, other.globalId,
                        restoredAuthority, List.of(restoredMap), restoredPlayers)),
                "conflicting saved Character UUID cannot hijack another player's identity");

        if (failures > 0) throw new AssertionError(failures + " GenesisPlayerIdentity tests failed");
        System.out.println("GenesisPlayerIdentity: ALL OK");
    }
}
