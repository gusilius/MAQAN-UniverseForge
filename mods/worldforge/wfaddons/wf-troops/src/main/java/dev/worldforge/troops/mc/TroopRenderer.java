package dev.worldforge.troops.mc;

import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.resources.ResourceLocation;

/** Солдат выглядит как игрок (модель Стива) с оружием и бронёй в руках и на теле. Только клиент. */
public class TroopRenderer extends HumanoidMobRenderer<TroopEntity, HumanoidModel<TroopEntity>> {
    private static final ResourceLocation TEXTURE = new ResourceLocation("textures/entity/steve.png");

    public TroopRenderer(EntityRendererProvider.Context ctx) {
        super(ctx, new HumanoidModel<>(ctx.bakeLayer(ModelLayers.PLAYER)), 0.5f);
        addLayer(new HumanoidArmorLayer<>(this, new HumanoidModel<>(ctx.bakeLayer(ModelLayers.PLAYER_INNER_ARMOR)),
                new HumanoidModel<>(ctx.bakeLayer(ModelLayers.PLAYER_OUTER_ARMOR)), ctx.getModelManager()));
    }

    @Override
    public ResourceLocation getTextureLocation(TroopEntity e) { return TEXTURE; }
}
