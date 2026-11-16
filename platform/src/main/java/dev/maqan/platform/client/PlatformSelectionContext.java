package dev.maqan.platform.client;

import net.minecraft.world.entity.Entity;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

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
            long revision,
            Entity previewEntity
    ) {}

    private static volatile Selection current;
    private static final Map<String, Function<String, Entity>> LIVE_ENTITY_RESOLVERS = new ConcurrentHashMap<>();
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
        publish(providerId, entityId, entityType, displayName, worldId, summary, null);
    }

    /** Publishes the actual live entity; the inspector renders it through its registered world renderer. */
    public static synchronized void publish(
            String providerId,
            String entityId,
            String entityType,
            String displayName,
            String worldId,
            String summary,
            Entity previewEntity
    ) {
        if (entityId == null || entityId.isBlank()) {
            clear(providerId);
            return;
        }
        String provider = clean(providerId);
        String type = clean(entityType);
        String name = clean(displayName);
        String world = clean(worldId);
        String details = clean(summary);
        // Screens publish during render; identical frames must not look like new
        // selections or force dependent inspectors to refresh every frame.
        if (current != null
                && current.providerId().equals(provider)
                && current.entityId().equals(entityId)
                && current.entityType().equals(type)
                && current.displayName().equals(name)
                && current.worldId().equals(world)
                && current.summary().equals(details)
                && current.previewEntity() == previewEntity) return;
        if (current != null)
            WorkspaceRuntime.removeIndexed(current.providerId(), current.entityId());
        current = new Selection(provider, entityId, type, name, world, details, ++revision, previewEntity);
        WorkspaceRuntime.index(new WorkspaceRuntime.SearchEntry(
                entityId, provider, type, name, world, details, java.util.List.of(type, provider)));
        WorkspaceRuntime.publish(WorkspaceRuntime.Severity.INFO, provider, "selection",
                "Selected " + name, entityId, world);
    }

    public static synchronized void clear(String providerId) {
        if (current == null) return;
        if (providerId == null || providerId.isBlank()
                || providerId.equals(current.providerId())) {
            WorkspaceRuntime.removeIndexed(current.providerId(), current.entityId());
            current = null;
            revision++;
        }
    }


    /** Register a provider-owned resolver for canonical IDs. */
    public static void registerLiveEntityResolver(String providerId, Function<String, Entity> resolver) {
        String provider = clean(providerId);
        if (provider.isBlank() || resolver == null) return;
        LIVE_ENTITY_RESOLVERS.put(provider, resolver);
    }

    public static void unregisterLiveEntityResolver(String providerId) {
        if (providerId != null) LIVE_ENTITY_RESOLVERS.remove(providerId.trim());
    }

    /** Resolve on demand so a selection never retains an entity from an unloaded world. */
    public static Entity resolveLiveEntity(Selection selection) {
        if (selection == null) return null;
        if (selection.previewEntity() != null) return selection.previewEntity();
        Function<String, Entity> resolver = LIVE_ENTITY_RESOLVERS.get(selection.providerId());
        if (resolver == null) return null;
        try { return resolver.apply(selection.entityId()); }
        catch (RuntimeException ignored) { return null; }
    }

    public static Selection current() {
        return current;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
