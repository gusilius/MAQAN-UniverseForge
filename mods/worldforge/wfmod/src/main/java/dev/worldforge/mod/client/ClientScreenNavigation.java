package dev.worldforge.mod.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/** Keeps modal provider screens inside MAQAN's shared workspace when Platform is installed. */
public final class ClientScreenNavigation {
    private ClientScreenNavigation() {}

    public static void open(Screen parent, Screen child) {
        if (parent == null || child == null) return;
        Minecraft mc = Minecraft.getInstance();
        if (invokeHost("pushEmbeddedChild", parent, child)) return;
        mc.setScreen(child);
    }

    public static void returnToParent(Screen child, Screen parent) {
        if (parent == null) return;
        Minecraft mc = Minecraft.getInstance();
        if (invokeHost("returnFromEmbeddedChild", child, parent)) return;
        mc.setScreen(parent);
    }

    private static boolean invokeHost(String method, Screen first, Screen second) {
        try {
            Class<?> host = Class.forName("dev.maqan.platform.client.PlatformShellScreen");
            Object result = host.getMethod(method, Screen.class, Screen.class).invoke(null, first, second);
            return Boolean.TRUE.equals(result);
        } catch (ReflectiveOperationException | LinkageError unavailable) {
            return false;
        }
    }
}
