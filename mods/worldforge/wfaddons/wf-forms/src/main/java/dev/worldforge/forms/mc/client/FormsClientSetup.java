package dev.worldforge.forms.mc.client;

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

/** Клиент wf_forms: окно «Состояния» (клавиша G). */
@Mod.EventBusSubscriber(modid = "wf_forms", bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class FormsClientSetup {
    public static final KeyMapping KEY = new KeyMapping("key.wf_forms.open", GLFW.GLFW_KEY_G, "key.categories.worldforge");

    private FormsClientSetup() {}

    @SubscribeEvent public static void keys(RegisterKeyMappingsEvent e) { e.register(KEY); }

    @SubscribeEvent
    public static void setup(FMLClientSetupEvent e) {
        e.enqueueWork(() -> ClientViews.register("wf_forms:journal", payload -> {
            try {
                Minecraft mc = Minecraft.getInstance();
                if (mc.screen instanceof FormsScreen s) s.setModel(payload); else mc.setScreen(new FormsScreen(payload, ClientViews::command));
            } catch (RuntimeException ex) {
                dev.worldforge.forms.mc.FormsMod.LOGGER.error("wf_forms: не удалось открыть окно: {}", ex.toString());
            }
        }));
        MinecraftForge.EVENT_BUS.addListener((TickEvent.ClientTickEvent t) -> {
            if (t.phase != TickEvent.Phase.END) return;
            var mc = Minecraft.getInstance();
            while (KEY.consumeClick()) if (mc.player != null && mc.screen == null) ClientViews.command("wfform");
        });
    }
}
