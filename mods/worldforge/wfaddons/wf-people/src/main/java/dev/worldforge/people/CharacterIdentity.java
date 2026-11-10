package dev.worldforge.people;

import dev.worldforge.mod.identity.WorldId;

import java.util.Objects;

/** Collision-free address of a Character inside one persistent WorldForge world and map. */
public record CharacterIdentity(WorldId worldId, String mapId, String characterId) {
    public CharacterIdentity {
        Objects.requireNonNull(worldId, "worldId");
        mapId = require(mapId, "mapId");
        characterId = require(characterId, "characterId");
    }

    private static String require(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value.trim();
    }
}
