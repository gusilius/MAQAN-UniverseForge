package dev.worldforge.settle.mc;

import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.resources.ResourceLocation;

/**
 * Full humanoid NPC body, not a villager head/body proxy.
 * Character equipment is projected onto this body from the canonical Character inventory.
 */
public class WFWorkerRenderer extends HumanoidMobRenderer<WFWorker, HumanoidModel<WFWorker>> {
    private static final ResourceLocation STEVE = new ResourceLocation("textures/entity/steve.png");
    private static final ResourceLocation ALEX = new ResourceLocation("textures/entity/alex.png");

    public WFWorkerRenderer(EntityRendererProvider.Context ctx) {
        super(ctx, new HumanoidModel<>(ctx.bakeLayer(ModelLayers.PLAYER)), 0.5f);
        addLayer(new HumanoidArmorLayer<>(this,
                new HumanoidModel<>(ctx.bakeLayer(ModelLayers.PLAYER_INNER_ARMOR)),
                new HumanoidModel<>(ctx.bakeLayer(ModelLayers.PLAYER_OUTER_ARMOR)),
                ctx.getModelManager()));
    }

    @Override
    public ResourceLocation getTextureLocation(WFWorker entity) {
        // A stable canonical Character key makes the skin survive body despawn/respawn.
        return (entity.appearanceVariant() & 1) == 0 ? STEVE : ALEX;
    }
}
