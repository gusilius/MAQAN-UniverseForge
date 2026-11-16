package dev.maqan.platform.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.maqan.platform.MaqanPlatform;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.screens.Screen;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.fml.ModList;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

@Mod.EventBusSubscriber(modid = MaqanPlatform.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class PlatformClient {
    private static final KeyMapping OPEN = new KeyMapping(
            "key.maqan_platform.open", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F10, "key.categories.maqan");
    private static final KeyMapping CAMERA_MODE = new KeyMapping(
            "key.maqan_platform.camera_mode", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F8, "key.categories.maqan");
    private static final KeyMapping MAP_MODE = new KeyMapping(
            "key.maqan_platform.map_mode", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F9, "key.categories.maqan");
    private PlatformClient() { }

    @SubscribeEvent
    public static void registerKeys(RegisterKeyMappingsEvent event) {
        event.register(OPEN);
        event.register(CAMERA_MODE);
        event.register(MAP_MODE);
    }

    @Mod.EventBusSubscriber(modid = MaqanPlatform.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
    public static final class Ticks {
        private Ticks() { }
        /**
         * Intercept legacy WorldForge screens before Minecraft installs them.
         * Their existing rendering, input and domain actions are hosted by the
         * corresponding MAQAN workspace instead of replacing the global shell.
         */
        @SubscribeEvent
        public static void routeWorldForgeScreens(ScreenEvent.Opening event) {
            Screen next = event.getScreen();
            if (next instanceof PlatformShellScreen) return;
            String className = next.getClass().getName();
            if (!className.startsWith("dev.worldforge.")) return;
            String packageRoot = className.substring("dev.worldforge.".length()).split("\\.")[0];
            String providerModId = packageRoot.equals("mod") ? "worldforge" : "wf_" + packageRoot;
            if (!ModList.get().isLoaded(providerModId) && !providerModId.equals("worldforge")) return;
            Minecraft mc = Minecraft.getInstance();
            if (mc.screen instanceof PlatformShellScreen shell
                    && shell.attachProviderView(providerModId, next)) {
                // Opening is cancellable: retain the current shell instance and
                // mount the provider view without closing/removing the shell.
                event.setCanceled(true);
                return;
            }
            event.setNewScreen(PlatformShellScreen.wrapProviderView(providerModId, next));
        }

        @SubscribeEvent
        public static void clientTick(TickEvent.ClientTickEvent event) {
            if (event.phase != TickEvent.Phase.END) return;
            Minecraft mc = Minecraft.getInstance();
            while (CAMERA_MODE.consumeClick()) {
                if (mc.player != null && mc.level != null)
                    MaqanCameraBridge.apply(WorkspaceViewState.cycleCameraMode());
            }
            while (MAP_MODE.consumeClick()) {
                if (mc.player != null && mc.level != null)
                    PlatformShellScreen.cycleMapModeFromHotkey();
            }
            while (OPEN.consumeClick()) {
                if (mc.screen instanceof PlatformShellScreen) mc.setScreen(null);
                else mc.setScreen(new PlatformShellScreen());
            }
        }
    }
}
