package com.maqan.galacticnavigator.navigation;

/**
 * Optional bridge from Atlas selections to the shared MAQAN Platform Inspector.
 * Atlas deliberately has no compile-time dependency on the Platform module.
 */
public final class NavigatorPlatformSelectionBridge {
    private static final String PROVIDER_ID = "maqan_galactic_navigator";
    private static final String PLATFORM_CONTEXT =
            "dev.maqan.platform.client.PlatformSelectionContext";

    private NavigatorPlatformSelectionBridge() {}

    public static void publish(String entityId, String entityType, String displayName,
                               String worldId, String summary) {
        try {
            Class<?> bridge = Class.forName(PLATFORM_CONTEXT);
            if (entityId == null || entityId.isBlank()) {
                bridge.getMethod("clear", String.class).invoke(null, PROVIDER_ID);
                return;
            }
            bridge.getMethod("publish", String.class, String.class, String.class,
                    String.class, String.class, String.class).invoke(null,
                    PROVIDER_ID, entityId, clean(entityType), clean(displayName),
                    clean(worldId), clean(summary));
        } catch (ClassNotFoundException ignored) {
            // Atlas remains fully functional when the optional Platform is absent.
        } catch (ReflectiveOperationException | LinkageError ignored) {
            // A host Inspector must never break Atlas interaction.
        }
    }

    public static void publishViewport(String dimension, double centerX, double centerZ,
                                       double zoom, double yaw, double pitch) {
        try {
            Class<?> state = Class.forName("dev.maqan.platform.client.WorkspaceMapState");
            state.getMethod("setViewport", String.class, double.class, double.class,
                    double.class, double.class, double.class).invoke(null,
                    clean(dimension), centerX, centerZ, zoom, yaw, pitch);
        } catch (ClassNotFoundException ignored) {
            // Atlas remains independent from the optional MAQAN Platform.
        } catch (ReflectiveOperationException | LinkageError ignored) {
            // A workspace bridge must never interrupt map rendering.
        }
    }

    public static void setViewType(String typeName) {
        if (typeName == null || typeName.isBlank()) return;
        try {
            Class<?> state = Class.forName("dev.maqan.platform.client.WorkspaceMapState");
            Class<?> type = Class.forName("dev.maqan.platform.client.WorkspaceMapState$ViewType");
            Object value = Enum.valueOf((Class<? extends Enum>) type.asSubclass(Enum.class),
                    typeName.trim().toUpperCase(java.util.Locale.ROOT));
            state.getMethod("setViewType", type).invoke(null, value);
        } catch (ClassNotFoundException ignored) {
            // Optional host not installed.
        } catch (ReflectiveOperationException | LinkageError | IllegalArgumentException ignored) {
            // Unknown future map types are ignored by older host versions.
        }
    }

    public static void clear() {
        publish("", "", "", "", "");
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
