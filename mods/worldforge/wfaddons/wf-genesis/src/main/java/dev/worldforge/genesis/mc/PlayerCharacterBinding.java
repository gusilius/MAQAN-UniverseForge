package dev.worldforge.genesis.mc;

import dev.worldforge.people.Character;
import dev.worldforge.people.CharacterAuthority;
import dev.worldforge.people.CharacterRegistry;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Resolves a Minecraft account to exactly one canonical WorldForge Character.
 *
 * Player UUID is an account key, not a second person identity. The saved global
 * Character UUID is authoritative when present; legacy saves can be recovered
 * through the genesis.player_uuid property. Any ambiguity or disagreement is
 * rejected so Genesis never silently creates a duplicate person.
 */
public final class PlayerCharacterBinding {
    private static final String PLAYER_UUID = "genesis.player_uuid";

    private PlayerCharacterBinding() { }

    public static Character resolveExisting(
            String playerUuid,
            String savedGlobalId,
            CharacterAuthority authority,
            Collection<CharacterRegistry> mapRegistries,
            CharacterRegistry playerRegistry) {
        String accountId = canonicalUuid(playerUuid, "player UUID");
        Objects.requireNonNull(authority, "authority");
        Objects.requireNonNull(mapRegistries, "mapRegistries");
        Objects.requireNonNull(playerRegistry, "playerRegistry");

        String savedId = savedGlobalId == null || savedGlobalId.isBlank()
                ? "" : canonicalUuid(savedGlobalId, "saved Character UUID");

        Map<String, Character> byGlobal = new LinkedHashMap<>();
        Map<String, Character> byAccount = new LinkedHashMap<>();
        for (CharacterRegistry registry : mapRegistries) {
            collect(registry, accountId, byGlobal, byAccount);
        }
        collect(playerRegistry, accountId, byGlobal, byAccount);

        // Authority is the canonical world-wide index. Include its match even
        // if a caller's map snapshot was taken before a lazily loaded map.
        if (!savedId.isEmpty()) {
            Character indexed = authority.getGlobal(savedId);
            if (indexed != null) byGlobal.put(indexed.globalId, indexed);
        }

        if (byAccount.size() > 1)
            throw new IllegalStateException("Minecraft player UUID is bound to multiple Characters");
        if (!savedId.isEmpty()) {
            Character saved = byGlobal.get(savedId);
            if (saved == null) {
                throw new IllegalStateException("Saved Character UUID does not exist; refusing to create a replacement");
            }
            if (!accountId.equals(String.valueOf(saved.props.get(PLAYER_UUID))))
                throw new IllegalStateException("Saved Character UUID belongs to a different Minecraft player");
            Character accountMatch = byAccount.get(accountId);
            if (accountMatch != null && accountMatch != saved)
                throw new IllegalStateException("Minecraft player UUID and saved Character UUID resolve to different Characters");
            return saved;
        }

        Character legacy = byAccount.get(accountId);
        if (legacy != null) return legacy;

        // No binding exists. The caller may create a new Character and persist
        // its global UUID back to player persistent data.
        return null;
    }

    private static void collect(
            CharacterRegistry registry,
            String accountId,
            Map<String, Character> byGlobal,
            Map<String, Character> byAccount) {
        if (registry == null) return;
        for (Character character : registry.all()) {
            Character previous = byGlobal.putIfAbsent(character.globalId, character);
            if (previous != null && previous != character)
                throw new IllegalStateException("Duplicate global Character UUID in registries: " + character.globalId);
            Object boundAccount = character.props.get(PLAYER_UUID);
            if (accountId.equals(boundAccount)) {
                Character existing = byAccount.putIfAbsent(accountId, character);
                if (existing != null && existing != character)
                    throw new IllegalStateException("Player UUID is bound to multiple Characters");
            }
        }
    }

    private static String canonicalUuid(String value, String label) {
        try {
            return UUID.fromString(Objects.requireNonNull(value, label)).toString();
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("Invalid " + label, ex);
        }
    }
}
