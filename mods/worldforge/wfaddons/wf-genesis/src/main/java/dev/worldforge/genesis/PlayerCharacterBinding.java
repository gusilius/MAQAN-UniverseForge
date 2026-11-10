package dev.worldforge.genesis;

import dev.worldforge.people.Character;
import dev.worldforge.people.CharacterAuthority;
import dev.worldforge.people.CharacterRegistry;

import java.util.Collection;
import java.util.Objects;

/**
 * Pure identity resolution for Genesis player profiles.
 * A player UUID and a saved global Character UUID must resolve to the same person.
 */
public final class PlayerCharacterBinding {
    private PlayerCharacterBinding() { }

    public static Character resolveExisting(String playerUuid, String savedGlobalId,
                                             CharacterAuthority authority,
                                             Collection<CharacterRegistry> mapRegistries,
                                             CharacterRegistry playerRegistry) {
        if (playerUuid == null || playerUuid.isBlank()) throw new IllegalArgumentException("playerUuid is required");
        Objects.requireNonNull(authority, "authority");
        Objects.requireNonNull(mapRegistries, "mapRegistries");
        Objects.requireNonNull(playerRegistry, "playerRegistry");

        Character saved = savedGlobalId == null || savedGlobalId.isBlank()
                ? null : authority.getGlobal(savedGlobalId);
        Character match = null;
        for (CharacterRegistry registry : mapRegistries) {
            if (registry == null) continue;
            match = matchPlayer(match, registry, playerUuid);
        }
        match = matchPlayer(match, playerRegistry, playerUuid);

        if (saved != null) {
            String boundPlayer = String.valueOf(saved.props.getOrDefault("genesis.player_uuid", ""));
            if (boundPlayer.isBlank() || !playerUuid.equals(boundPlayer))
                throw new IllegalStateException("Saved Character UUID is not bound to this Genesis player");
        }
        if (saved != null && match != null && !saved.globalId.equals(match.globalId))
            throw new IllegalStateException("Player UUID and saved Character UUID resolve to different people");
        return saved != null ? saved : match;
    }

    /** New Genesis identities always live in the dedicated world-wide player partition. */
    public static Character resolveOrCreate(String playerUuid, String savedGlobalId,
                                             CharacterAuthority authority,
                                             Collection<CharacterRegistry> mapRegistries,
                                             CharacterRegistry playerRegistry,
                                             String name, String culture, String gender, int birthYear) {
        Character existing = resolveExisting(playerUuid, savedGlobalId, authority, mapRegistries, playerRegistry);
        if (existing != null) return existing;
        if (authority.registry("__players__") != playerRegistry)
            throw new IllegalStateException("Dedicated world-wide player Character partition is not registered");
        Character created = playerRegistry.create(name, "", culture, gender, birthYear);
        created.props.put("genesis.player_uuid", playerUuid);
        return created;
    }

    private static Character matchPlayer(Character current, CharacterRegistry registry, String playerUuid) {
        for (Character candidate : registry.all()) {
            if (!playerUuid.equals(String.valueOf(candidate.props.get("genesis.player_uuid")))) continue;
            if (current != null && !current.globalId.equals(candidate.globalId))
                throw new IllegalStateException("Player UUID is bound to multiple WorldForge Characters: " + playerUuid);
            current = candidate;
        }
        return current;
    }
}
