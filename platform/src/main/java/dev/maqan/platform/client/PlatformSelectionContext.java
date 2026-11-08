package dev.maqan.platform.client;

/**
 * Small, version-neutral selection contract shared by MAQAN workspaces.
 *
 * Providers publish identity and display metadata only. Domain objects,
 * simulation state and persistence remain owned by the provider module.
 */
public final class PlatformSelectionContext {
    public record Selection(
            String providerId,
            String entityId,
            String entityType,
            String displayName,
            String worldId,
            String summary,
            long revision
    ) {}

    private static volatile Selection current;
    private static long revision;

    private PlatformSelectionContext() {}

    public static synchronized void publish(
            String providerId,
            String entityId,
            String entityType,
            String displayName,
            String worldId,
            String summary
    ) {
        if (entityId == null || entityId.isBlank()) {
            clear(providerId);
            return;
        }
        current = new Selection(
                clean(providerId),
                entityId,
                clean(entityType),
                clean(displayName),
                clean(worldId),
                clean(summary),
                ++revision
        );
    }

    public static synchronized void clear(String providerId) {
        if (current == null) return;
        if (providerId == null || providerId.isBlank()
                || providerId.equals(current.providerId())) {
            current = null;
            revision++;
        }
    }

    public static Selection current() {
        return current;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
