package dev.worldforge.mod;

import net.minecraft.server.MinecraftServer;

import java.util.concurrent.ConcurrentSkipListMap;

/**
 * Cross-addon bridge for the visual identity of a designated army/squad leader.
 * Providers resolve canonical Character IDs; callers never create duplicate Character objects.
 */
public final class LeaderVisualProviders {
    public record Visual(String entityUuid, String entityTypeId, String modelProfile, String displayName) {
        public Visual {
            entityUuid = entityUuid == null ? "" : entityUuid;
            entityTypeId = entityTypeId == null ? "" : entityTypeId;
            modelProfile = modelProfile == null ? "" : modelProfile;
            displayName = displayName == null ? "" : displayName;
        }
    }

    @FunctionalInterface
    public interface Provider {
        /** Returns null when the supplied global Character ID is not a live canonical person. */
        Visual resolve(MinecraftServer server, String globalCharacterId);
    }

    private static final ConcurrentSkipListMap<String, Provider> PROVIDERS = new ConcurrentSkipListMap<>();

    private LeaderVisualProviders() { }

    public static void register(String id, Provider provider) {
        if (id == null || id.isBlank() || provider == null)
            throw new IllegalArgumentException("A leader visual provider id and implementation are required");
        PROVIDERS.put(id, provider);
    }

    public static void unregister(String id) {
        if (id != null) PROVIDERS.remove(id);
    }

    public static Visual resolve(MinecraftServer server, String globalCharacterId) {
        if (server == null || globalCharacterId == null || globalCharacterId.isBlank()) return null;
        for (var entry : PROVIDERS.entrySet()) {
            try {
                Visual visual = entry.getValue().resolve(server, globalCharacterId);
                if (visual != null) return visual;
            } catch (RuntimeException failure) {
                WorldForgeMod.LOGGER.warn("Leader visual provider '{}' failed for Character {}: {}",
                        entry.getKey(), globalCharacterId, failure.toString());
            }
        }
        return null;
    }
}
