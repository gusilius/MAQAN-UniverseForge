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
