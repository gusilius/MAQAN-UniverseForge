package dev.worldforge.troops.mc;

import dev.worldforge.troops.mc.client.TroopRenderer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Client-only registration for the physical soldier renderer. */
@Mod.EventBusSubscriber(modid = TroopsMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class TroopsClientSetup {
    private TroopsClientSetup() {}

    @SubscribeEvent
    public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(TroopsMod.TROOP.get(), TroopRenderer::new);
    }
}
