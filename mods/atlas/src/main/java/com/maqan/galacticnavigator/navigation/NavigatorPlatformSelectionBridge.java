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

    public static void clear() {
        publish("", "", "", "", "");
    }

    /** Route a global MAQAN search result back to the active Atlas screen. */
    public static void registerEntityOpener(java.util.function.Consumer<String> opener) {
        if (opener == null) return;
        try {
            Class<?> runtime = Class.forName("dev.maqan.platform.client.WorkspaceRuntime");
            java.util.function.Consumer<Object> callback = entry -> {
                try {
                    String provider = (String) entry.getClass().getMethod("providerId").invoke(entry);
                    String entityId = (String) entry.getClass().getMethod("id").invoke(entry);
                    if (PROVIDER_ID.equals(provider)) opener.accept(entityId);
                } catch (ReflectiveOperationException ignored) {
                    // Invalid search payload; ignore without breaking Atlas.
                }
            };
            runtime.getMethod("registerEntityOpener", String.class, java.util.function.Consumer.class)
                    .invoke(null, PROVIDER_ID, callback);
        } catch (ReflectiveOperationException | LinkageError ignored) {
            // Atlas remains independently usable without MAQAN Platform.
        }
    }

    /** Register an Atlas action in the shared workspace context menu. */
    public static void registerAction(String id, String label,
                                      java.util.function.Predicate<String> visibleForEntity,
                                      java.util.function.Consumer<String> action) {
        if (visibleForEntity == null || action == null) return;
        try {
            Class<?> runtime = Class.forName("dev.maqan.platform.client.WorkspaceRuntime");
            Class<?> actionType = Class.forName("dev.maqan.platform.client.WorkspaceRuntime$Action");
            java.util.function.Predicate<Object> visible = selection -> {
                try {
                    String owner = (String) selection.getClass().getMethod("providerId").invoke(selection);
                    String entityId = (String) selection.getClass().getMethod("entityId").invoke(selection);
                    return PROVIDER_ID.equals(owner) && visibleForEntity.test(entityId);
                } catch (ReflectiveOperationException | RuntimeException error) {
                    return false;
                }
            };
            java.util.function.Consumer<Object> execute = selection -> {
                try {
                    String owner = (String) selection.getClass().getMethod("providerId").invoke(selection);
                    String entityId = (String) selection.getClass().getMethod("entityId").invoke(selection);
                    if (!PROVIDER_ID.equals(owner) || !visibleForEntity.test(entityId))
                        throw new IllegalStateException("Atlas selection is no longer valid");
                    action.accept(entityId);
                } catch (ReflectiveOperationException error) {
                    throw new IllegalStateException("Unable to resolve Atlas selection", error);
                }
            };
            Object descriptor = actionType.getConstructor(String.class, String.class, String.class,
                    java.util.function.Predicate.class, java.util.function.Consumer.class)
                    .newInstance(id, label, PROVIDER_ID, visible, execute);
            runtime.getMethod("registerAction", actionType).invoke(null, descriptor);
        } catch (ReflectiveOperationException | LinkageError ignored) {
            // Atlas remains independently usable without MAQAN Platform.
        }
    }

    /**
     * Register an Atlas-owned inspector without introducing a compile-time
     * dependency on the optional Platform module. Rows are {section,label,value}.
     */
    public static void registerInspector(
            java.util.function.Function<String, java.util.List<String[]>> fields) {
        if (fields == null) return;
        try {
            Class<?> runtime = Class.forName("dev.maqan.platform.client.WorkspaceRuntime");
            Class<?> providerType = Class.forName(
                    "dev.maqan.platform.client.WorkspaceRuntime$InspectorProvider");
            Class<?> fieldType = Class.forName(
                    "dev.maqan.platform.client.WorkspaceRuntime$InspectorField");
            Object provider = java.lang.reflect.Proxy.newProxyInstance(providerType.getClassLoader(),
                    new Class<?>[]{providerType}, (proxy, method, args) -> {
                        if (!method.getName().equals("inspect")) return null;
                        Object selection = args[0];
                        String entityId = (String) selection.getClass().getMethod("entityId").invoke(selection);
                        java.util.List<String[]> rows = fields.apply(entityId);
                        java.util.List<Object> result = new java.util.ArrayList<>();
                        if (rows != null) for (String[] row : rows) {
                            if (row == null || row.length < 3) continue;
                            result.add(fieldType.getConstructor(String.class, String.class, String.class)
                                    .newInstance(row[0], row[1], row[2]));
                        }
                        return result;
                    });
            runtime.getMethod("registerInspector", String.class, providerType)
                    .invoke(null, PROVIDER_ID, provider);
        } catch (ReflectiveOperationException | LinkageError ignored) {
            // Atlas remains independent when MAQAN Platform is not installed.
        }
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
