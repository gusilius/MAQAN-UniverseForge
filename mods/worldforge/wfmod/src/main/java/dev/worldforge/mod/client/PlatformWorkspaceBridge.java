package dev.worldforge.mod.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/**
 * Optional, reflection-only bridge from WorldForge screens to MAQAN Platform.
 * WorldForge remains independently installable and owns all simulation data.
 */
public final class PlatformWorkspaceBridge {
    private static final String CONTEXT = "dev.maqan.platform.client.PlatformSelectionContext";
    private static final String SHELL = "dev.maqan.platform.client.PlatformShellScreen";

    private PlatformWorkspaceBridge() {}

    public static void publish(String providerId, String entityId, String entityType,
                               String displayName, String summary) {
        if (entityId == null || entityId.isBlank()) {
            clear(providerId);
            return;
        }
        try {
            Class<?> context = Class.forName(CONTEXT);
            context.getMethod("publish", String.class, String.class, String.class,
                    String.class, String.class, String.class).invoke(null,
                    clean(providerId), entityId, clean(entityType), clean(displayName),
                    worldId(), clean(summary));
        } catch (ReflectiveOperationException | LinkageError ignored) {
            // The Platform is optional; domain screens must keep working without it.
        }
    }

    public static void clear(String providerId) {
        try {
            Class<?> context = Class.forName(CONTEXT);
            context.getMethod("clear", String.class).invoke(null, clean(providerId));
        } catch (ReflectiveOperationException | LinkageError ignored) {
            // No optional host is installed.
        }
    }

    public static String worldId() {
        Minecraft mc = Minecraft.getInstance();
        return mc.level == null ? "" : mc.level.dimension().location().toString();
    }

    /** Register an action that is shown in MAQAN's context menu for this provider's selection. */
    public static void registerAction(String id, String label, String providerId, Runnable action) {
        if (action == null) return;
        try {
            Class<?> runtime = Class.forName("dev.maqan.platform.client.WorkspaceRuntime");
            Class<?> actionType = Class.forName("dev.maqan.platform.client.WorkspaceRuntime$Action");
            java.util.function.Predicate<Object> visible = selection -> true;
            java.util.function.Consumer<Object> execute = selection -> action.run();
            Object descriptor = actionType.getConstructor(String.class, String.class, String.class,
                    java.util.function.Predicate.class, java.util.function.Consumer.class)
                    .newInstance(id, label, providerId, visible, execute);
            runtime.getMethod("registerAction", actionType).invoke(null, descriptor);
        } catch (ReflectiveOperationException | LinkageError ignored) {
            // Optional host not installed; provider's own controls remain available.
        }
    }

    /** Route a selected global-search result back to the provider's entity focus/navigation logic. */
    public static void registerEntityOpener(String providerId, java.util.function.Consumer<String> opener) {
        if (opener == null) return;
        try {
            Class<?> runtime = Class.forName("dev.maqan.platform.client.WorkspaceRuntime");
            java.util.function.Consumer<Object> callback = entry -> {
                try { opener.accept((String) entry.getClass().getMethod("id").invoke(entry)); }
                catch (ReflectiveOperationException ignored) { }
            };
            runtime.getMethod("registerEntityOpener", String.class, java.util.function.Consumer.class)
                    .invoke(null, providerId, callback);
        } catch (ReflectiveOperationException | LinkageError ignored) {
            // Optional host not installed.
        }
    }

    /** Provider-specific inspector fields. Each row is {section, label, value}. */
    public static void registerInspector(String providerId,
                                         java.util.function.Function<String, java.util.List<String[]>> fields) {
        if (fields == null) return;
        try {
            Class<?> runtime = Class.forName("dev.maqan.platform.client.WorkspaceRuntime");
            Class<?> providerType = Class.forName("dev.maqan.platform.client.WorkspaceRuntime$InspectorProvider");
            Class<?> fieldType = Class.forName("dev.maqan.platform.client.WorkspaceRuntime$InspectorField");
            Object provider = java.lang.reflect.Proxy.newProxyInstance(providerType.getClassLoader(),
                    new Class<?>[]{providerType}, (proxy, method, args) -> {
                        if (!method.getName().equals("inspect")) return null;
                        Object selection = args[0];
                        String entityId = (String) selection.getClass().getMethod("entityId").invoke(selection);
                        java.util.List<String[]> values = fields.apply(entityId);
                        java.util.List<Object> result = new java.util.ArrayList<>();
                        if (values != null) for (String[] row : values) {
                            if (row == null || row.length < 3) continue;
                            result.add(fieldType.getConstructor(String.class, String.class, String.class)
                                    .newInstance(row[0], row[1], row[2]));
                        }
                        return result;
                    });
            runtime.getMethod("registerInspector", String.class, providerType)
                    .invoke(null, providerId, provider);
        } catch (ReflectiveOperationException | LinkageError ignored) {
            // Optional host not installed.
        }
    }

    @FunctionalInterface
    public interface MapLayerRenderer {
        void render(Object graphics, int x, int y, int width, int height, float opacity);
    }

    /** Register a map overlay without linking WorldForge against the optional Platform jar. */
    public static void registerMapLayer(String id, String label, String providerId, int order,
                                        boolean visible, float opacity, MapLayerRenderer renderer) {
        if (renderer == null) return;
        try {
            Class<?> registry = Class.forName("dev.maqan.platform.client.WorkspaceMapLayerRegistry");
            Class<?> rendererType = Class.forName("dev.maqan.platform.client.WorkspaceMapLayerRegistry$Renderer");
            Class<?> layerType = Class.forName("dev.maqan.platform.client.WorkspaceMapLayerRegistry$Layer");
            Object callback = java.lang.reflect.Proxy.newProxyInstance(rendererType.getClassLoader(),
                    new Class<?>[]{rendererType}, (proxy, method, args) -> {
                        if (method.getName().equals("render")) {
                            renderer.render(args[0], (Integer) args[1], (Integer) args[2],
                                    (Integer) args[3], (Integer) args[4], (Float) args[5]);
                        }
                        return null;
                    });
            Object layer = layerType.getConstructor(String.class, String.class, String.class,
                    int.class, boolean.class, float.class, rendererType)
                    .newInstance(id, label, providerId, order, visible, opacity, callback);
            registry.getMethod("register", layerType).invoke(null, layer);
        } catch (ReflectiveOperationException | LinkageError ignored) {
            // Optional host not installed.
        }
    }

    /** Detach a hosted view without closing the user's entire MAQAN workspace. */
    public static boolean closeEmbedded(Screen view) {
        try {
            Class<?> shell = Class.forName(SHELL);
            Object result = shell.getMethod("closeEmbeddedProviderView", Screen.class).invoke(null, view);
            return Boolean.TRUE.equals(result);
        } catch (ReflectiveOperationException | LinkageError ignored) {
            return false;
        }
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
