package dev.worldforge.troops;

import dev.worldforge.mod.identity.WorldId;

import java.util.Objects;

/**
 * Stable identity of one strategic army inside a saved world and one loaded map.
 * ArmyEngine IDs are intentionally map-local and must never be used as global keys.
 */
public record ArmyRef(WorldId worldId, String mapId, int armyId) {
    public ArmyRef {
        Objects.requireNonNull(worldId, "worldId");
        if (mapId == null || mapId.isBlank()) throw new IllegalArgumentException("mapId is required");
        mapId = mapId.trim();
        if (mapId.length() > 256) throw new IllegalArgumentException("mapId is too long");
        if (armyId <= 0) throw new IllegalArgumentException("armyId must be positive");
    }

    public String stableKey() {
        return worldId + "/" + mapId + "/" + armyId;
    }
}
