package dev.worldforge.relations.mc.client;

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

/** Клиент wf_relations: окно отношений (клавиша B). */
@Mod.EventBusSubscriber(modid = "wf_relations", bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class RelationsClientSetup {
    public static final KeyMapping KEY = new KeyMapping("key.wf_relations.open", GLFW.GLFW_KEY_B, "key.categories.worldforge");

    private RelationsClientSetup() {}

    @SubscribeEvent
    public static void keys(RegisterKeyMappingsEvent e) { e.register(KEY); }

    @SubscribeEvent
    public static void setup(FMLClientSetupEvent e) {
        e.enqueueWork(() -> ClientViews.register("wf_relations:relations", payload -> {
            try {
                Minecraft mc = Minecraft.getInstance();
                if (mc.screen instanceof RelationsScreen rs) rs.setModel(payload); else mc.setScreen(new RelationsScreen(payload, ClientViews::command));
            } catch (RuntimeException ex) {
                dev.worldforge.relations.mc.RelationsMod.LOGGER.error("wf_relations: окно отношений: {}", ex.toString());
            }
        }));
        MinecraftForge.EVENT_BUS.addListener((TickEvent.ClientTickEvent t) -> {
            if (t.phase != TickEvent.Phase.END) return;
            var mc = Minecraft.getInstance();
            while (KEY.consumeClick()) if (mc.player != null && mc.screen == null) ClientViews.command("wfrel");
        });
    }
}
