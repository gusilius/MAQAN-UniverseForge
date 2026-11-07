package dev.worldforge.court.mc.client;

import dev.worldforge.mod.client.ClientViews;
import net.minecraft.client.KeyMapping;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import org.lwjgl.glfw.GLFW;

/** Клиент wf_people: регистрация окна двора и клавиша V. */
@Mod.EventBusSubscriber(modid = "wf_court", bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class CourtClientSetup {
    public static final KeyMapping KEY = new KeyMapping("key.wf_court.open", GLFW.GLFW_KEY_V, "key.categories.worldforge");

    private CourtClientSetup() {}

    @SubscribeEvent
    public static void keys(RegisterKeyMappingsEvent e) { e.register(KEY); }

    @SubscribeEvent
    public static void setup(FMLClientSetupEvent e) {
        e.enqueueWork(CourtClient::register);
        MinecraftForge.EVENT_BUS.addListener((TickEvent.ClientTickEvent t) -> {
            if (t.phase != TickEvent.Phase.END) return;
            var mc = net.minecraft.client.Minecraft.getInstance();
            while (KEY.consumeClick()) if (mc.player != null && mc.screen == null) ClientViews.command("wfcourt");
        });
    }
}
