package dev.worldforge.settle.mc;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Клиентская регистрация рендерера рабочего (шина мода, только Dist.CLIENT). */
@Mod.EventBusSubscriber(modid = SettleMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class ClientSetup {
    private ClientSetup() {}

    public static final net.minecraft.client.KeyMapping PANEL_KEY = new net.minecraft.client.KeyMapping("key.wf_settle.panel", org.lwjgl.glfw.GLFW.GLFW_KEY_J, "key.categories.worldforge");

    @SubscribeEvent
    public static void keys(net.minecraftforge.client.event.RegisterKeyMappingsEvent e) {
        e.register(PANEL_KEY);
    }

    @SubscribeEvent
    public static void setup(net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent e) {
        e.enqueueWork(dev.worldforge.settle.mc.client.SettleClient::register);
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.addListener((net.minecraftforge.event.TickEvent.ClientTickEvent t) -> {
            if (t.phase != net.minecraftforge.event.TickEvent.Phase.END) return;
            var mc = net.minecraft.client.Minecraft.getInstance();
            while (PANEL_KEY.consumeClick()) if (mc.player != null && mc.screen == null) dev.worldforge.mod.client.ClientViews.command("wfsettle view panel");
        });
    }

    @SubscribeEvent
    public static void renderers(EntityRenderersEvent.RegisterRenderers e) {
        e.registerEntityRenderer(SettleMod.WORKER.get(), WFWorkerRenderer::new);
    }
}
