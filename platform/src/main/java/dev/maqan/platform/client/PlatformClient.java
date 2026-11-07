package dev.maqan.platform.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.maqan.platform.MaqanPlatform;
import net.minecraft.client.KeyMapping;
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
            "key.maqan_platform.open", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_P, "key.categories.maqan");
    private PlatformClient() { }

    @SubscribeEvent
    public static void registerKeys(RegisterKeyMappingsEvent event) { event.register(OPEN); }

    @Mod.EventBusSubscriber(modid = MaqanPlatform.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
    public static final class Ticks {
        private Ticks() { }
        @SubscribeEvent
        public static void clientTick(TickEvent.ClientTickEvent event) {
            if (event.phase != TickEvent.Phase.END) return;
            Minecraft mc = Minecraft.getInstance();
            while (OPEN.consumeClick()) {
                if (mc.screen == null) mc.setScreen(new PlatformShellScreen());
            }
        }
    }
}
