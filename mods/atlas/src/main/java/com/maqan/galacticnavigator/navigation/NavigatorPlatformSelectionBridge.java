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

    /** Returns the last shared viewport as centerX, centerZ, zoom, yaw and pitch. */
    public static double[] loadViewport(String dimension) {
        try {
            Class<?> state = Class.forName("dev.maqan.platform.client.WorkspaceMapState");
            Object viewport = state.getMethod("viewport").invoke(null);
            if (viewport == null) return null;
            Class<?> type = viewport.getClass();
            String savedDimension = (String) type.getMethod("dimension").invoke(viewport);
            if (!clean(dimension).equals(savedDimension)) return null;
            double[] values = new double[]{
                    ((Number) type.getMethod("centerX").invoke(viewport)).doubleValue(),
                    ((Number) type.getMethod("centerZ").invoke(viewport)).doubleValue(),
                    ((Number) type.getMethod("zoom").invoke(viewport)).doubleValue(),
                    ((Number) type.getMethod("yaw").invoke(viewport)).doubleValue(),
                    ((Number) type.getMethod("pitch").invoke(viewport)).doubleValue()};
            for (double value : values) if (!Double.isFinite(value)) return null;
            if (values[2] <= 0) return null;
            return values;
        } catch (ClassNotFoundException ignored) {
            return null;
        } catch (ReflectiveOperationException | LinkageError | ClassCastException ignored) {
            return null;
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
