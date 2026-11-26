package com.maqan.galacticnavigator.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/**
 * UI-only bridge between Atlas view navigation and the optional MAQAN Platform.
 * It never changes Atlas actions or domain state; it only keeps view transitions
 * inside the global workspace when that workspace owns the current Minecraft screen.
 */
public final class MaqanScreenHost {
    private static final String PLATFORM_SCREEN =
            "dev.maqan.platform.client.PlatformShellScreen";

    private MaqanScreenHost() {}

    public static void open(Minecraft minecraft, Screen view) {
        if (minecraft == null || view == null) return;
        if (isHosted(minecraft)) {
            invokeHost(minecraft, "showEmbeddedView", view);
            return;
        }
        minecraft.setScreen(view);
    }

    public static void back(Minecraft minecraft, Screen parent) {
        if (minecraft == null || parent == null) return;
        if (isHosted(minecraft)) {
            invokeHost(minecraft, "showEmbeddedView", parent);
            return;
        }
        minecraft.setScreen(parent);
    }

    private static boolean isHosted(Minecraft minecraft) {
        return minecraft.screen != null
                && PLATFORM_SCREEN.equals(minecraft.screen.getClass().getName());
    }

    private static void invokeHost(Minecraft minecraft, String method, Screen view) {
        try {
            minecraft.screen.getClass().getMethod(method, Screen.class).invoke(minecraft.screen, view);
        } catch (ReflectiveOperationException | LinkageError error) {
            // Preserve the current global shell rather than unexpectedly replacing it
            // with a legacy screen if a mismatched Platform version is installed.
            com.mojang.logging.LogUtils.getLogger().error(
                    "Unable to route Atlas view {} through MAQAN Platform", view.getClass().getName(), error);
        }
    }
}
