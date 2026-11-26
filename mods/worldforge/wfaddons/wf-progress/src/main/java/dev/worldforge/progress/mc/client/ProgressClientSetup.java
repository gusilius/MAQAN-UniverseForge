package dev.worldforge.progress.mc.client;

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

/** Клиент wf_progress: окно прокачки (клавиша P). Определения приходят отдельным сообщением и запоминаются. */
@Mod.EventBusSubscriber(modid = "wf_progress", bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class ProgressClientSetup {
    public static final KeyMapping KEY = new KeyMapping("key.wf_progress.open", GLFW.GLFW_KEY_P, "key.categories.worldforge");
    static volatile String defs = "";

    private ProgressClientSetup() {}

    @SubscribeEvent
    public static void keys(RegisterKeyMappingsEvent e) { e.register(KEY); }

    @SubscribeEvent
    public static void setup(FMLClientSetupEvent e) {
        e.enqueueWork(() -> {
            ClientViews.register("wf_progress:defs", p -> defs = p);
            ClientViews.register("wf_progress:profile", payload -> {
                try {
                    Minecraft mc = Minecraft.getInstance();
                    if (mc.screen instanceof ProgressScreen s) s.setModel(payload);
                    else mc.setScreen(new ProgressScreen(defs, payload, ClientViews::command));
                } catch (RuntimeException ex) {
                    dev.worldforge.progress.mc.ProgressMod.LOGGER.error("wf_progress: не удалось открыть окно: {}", ex.toString());
                }
            });
        });
        MinecraftForge.EVENT_BUS.addListener((TickEvent.ClientTickEvent t) -> {
            if (t.phase != TickEvent.Phase.END) return;
            var mc = Minecraft.getInstance();
            while (KEY.consumeClick()) if (mc.player != null && mc.screen == null) ClientViews.command("wfprog");
        });
    }
}
