package dev.worldforge.origins.mc.client;

import dev.worldforge.mod.client.ClientViews;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import org.lwjgl.glfw.GLFW;

/** Клиент wf_origins: окно происхождения (клавиша K). */
@Mod.EventBusSubscriber(modid = "wf_origins", bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class OriginsClientSetup {
    public static final KeyMapping KEY = new KeyMapping("key.wf_origins.open", GLFW.GLFW_KEY_K, "key.categories.worldforge");

    private OriginsClientSetup() {}

    @SubscribeEvent public static void keys(RegisterKeyMappingsEvent e) { e.register(KEY); }

    @SubscribeEvent
    public static void setup(FMLClientSetupEvent e) {
        e.enqueueWork(() -> ClientViews.register("wf_origins:origins", payload -> {
            try {
                Minecraft mc = Minecraft.getInstance();
                if (mc.screen instanceof OriginsScreen s) s.setModel(payload); else mc.setScreen(new OriginsScreen(payload, ClientViews::command));
            } catch (RuntimeException ex) { dev.worldforge.origins.mc.OriginsMod.LOGGER.error("wf_origins: не удалось открыть окно: {}", ex.toString()); }
        }));
        MinecraftForge.EVENT_BUS.addListener((TickEvent.ClientTickEvent t) -> {
            if (t.phase != TickEvent.Phase.END) return;
            var mc = Minecraft.getInstance();
            while (KEY.consumeClick()) if (mc.player != null && mc.screen == null) ClientViews.command("wforigin");
        });
    }
}
