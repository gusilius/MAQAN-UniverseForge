package dev.worldforge.people.mc;

import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Только клиент: рендерер тела (модель игрока + ванильные скины Стив/Алекс). */
@Mod.EventBusSubscriber(modid = PeopleMod.ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class ClientSetup {
    private ClientSetup() {}

    @SubscribeEvent
    public static void renderers(EntityRenderersEvent.RegisterRenderers e) {
        e.registerEntityRenderer(PeopleMod.NPC.get(), NpcRenderer::new);
    }

    static final class NpcRenderer extends HumanoidMobRenderer<WFNpc, HumanoidModel<WFNpc>> {
        private static final ResourceLocation MALE = new ResourceLocation("textures/entity/player/wide/steve.png");
        private static final ResourceLocation FEMALE = new ResourceLocation("textures/entity/player/wide/alex.png");

        NpcRenderer(EntityRendererProvider.Context ctx) {
            super(ctx, new HumanoidModel<>(ctx.bakeLayer(ModelLayers.PLAYER)), 0.5F);
        }

        @Override
        public ResourceLocation getTextureLocation(WFNpc npc) {
            return npc.variant() == 1 ? FEMALE : MALE;
        }
    }
}
