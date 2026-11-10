package dev.worldforge.people;

import dev.worldforge.mod.identity.WorldId;

import java.util.Objects;
import java.util.UUID;

/** Stable address of one person; globalCharacterId survives map-local legacy key collisions. */
public record CharacterIdentity(WorldId worldId, String mapId, String characterId, String globalCharacterId) {
    public CharacterIdentity {
        Objects.requireNonNull(worldId, "worldId");
        mapId = require(mapId, "mapId");
        characterId = require(characterId, "characterId");
        globalCharacterId = UUID.fromString(require(globalCharacterId, "globalCharacterId")).toString();
    }

    private static String require(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value.trim();
    }
}
