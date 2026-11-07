package dev.worldforge.genesis.mc.client;

import dev.worldforge.genesis.mc.GenesisMod;
import dev.worldforge.mod.client.ClientViews;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.client.event.RenderPlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

/** Клиент wf_genesis: окно мастера, облик игроков (рост, телосложение, причёска, особенности). */
@Mod.EventBusSubscriber(modid = "wf_genesis", bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class GenesisClientSetup {
    private GenesisClientSetup() {}

    @SubscribeEvent
    public static void setup(FMLClientSetupEvent e) {
        e.enqueueWork(() -> {
            GenesisMod.CLIENT_SCALE = (p, key) -> { var l = ClientLooks.get(p.getUUID()); return l == null ? 1.0 : l.scale(key); };
            ClientViews.register(GenesisMod.VIEW, payload -> {
                try {
                    Minecraft mc = Minecraft.getInstance();
                    if (mc.screen instanceof GenesisScreen s) s.setModel(payload); else mc.setScreen(new GenesisScreen(payload, ClientViews::command));
                } catch (RuntimeException ex) { GenesisMod.LOGGER.error("wf_genesis: не удалось открыть окно: {}", ex.toString()); }
            });
            ClientViews.register(GenesisMod.VIEW + "_close", payload -> {
                Minecraft mc = Minecraft.getInstance();
                if (mc.screen instanceof GenesisScreen s) { s.finish(); mc.setScreen(null); }
            });
            ClientViews.register(GenesisMod.LOOKS, ClientLooks::accept);
        });
    }

    @SubscribeEvent
    public static void layers(EntityRenderersEvent.AddLayers e) {
        for (String skin : e.getSkins()) {
            PlayerRenderer r = e.getSkin(skin);
            if (r != null) r.addLayer(new FeatureLayer(r));
        }
    }

    /** Масштаб тела при отрисовке игрока. */
    @Mod.EventBusSubscriber(modid = "wf_genesis", bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
    public static final class Scale {
        private Scale() {}

        @SubscribeEvent
        public static void pre(RenderPlayerEvent.Pre e) {
            var l = ClientLooks.get(e.getEntity().getUUID());
            if (l == null) return;
            e.getPoseStack().pushPose();
            double sy = l.scale("height"), sx = l.scale("width");
            e.getPoseStack().scale((float) sx, (float) sy, (float) sx);
        }

        @SubscribeEvent
        public static void post(RenderPlayerEvent.Post e) {
            if (ClientLooks.get(e.getEntity().getUUID()) != null) e.getPoseStack().popPose();
        }
    }
}
