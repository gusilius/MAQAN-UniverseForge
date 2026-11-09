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
