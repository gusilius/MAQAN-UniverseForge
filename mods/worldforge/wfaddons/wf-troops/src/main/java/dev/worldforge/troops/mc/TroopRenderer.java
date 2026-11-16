package dev.worldforge.troops.mc;

import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.resources.ResourceLocation;
import com.mojang.blaze3d.vertex.PoseStack;

/** Солдат выглядит как игрок (модель Стива) с оружием и бронёй в руках и на теле. Только клиент. */
public class TroopRenderer extends HumanoidMobRenderer<TroopEntity, PlayerModel<TroopEntity>> {
    private static final ResourceLocation STEVE = new ResourceLocation("textures/entity/steve.png");
    private static final ResourceLocation ALEX = new ResourceLocation("textures/entity/alex.png");

    public TroopRenderer(EntityRendererProvider.Context ctx) {
        super(ctx, new PlayerModel<>(ctx.bakeLayer(ModelLayers.PLAYER), false), 0.5f);
        addLayer(new HumanoidArmorLayer<>(this, new HumanoidModel<>(ctx.bakeLayer(ModelLayers.PLAYER_INNER_ARMOR)),
                new HumanoidModel<>(ctx.bakeLayer(ModelLayers.PLAYER_OUTER_ARMOR)), ctx.getModelManager()));
    }

    /** Small unit-class proportions make the RTS battlefield easier to read at a glance. */
    @Override
    protected void scale(TroopEntity entity, PoseStack pose, float partialTick) {
        float factor = switch (entity.unitId()) {
            case "knight" -> 1.10f;
            case "light_cavalry" -> 1.07f;
            case "footman", "pikeman", "crossbowman" -> 1.03f;
            case "scout", "militia_archer" -> 0.94f;
            case "ram", "catapult" -> 1.06f;
            default -> 1.0f;
        };
        pose.scale(factor, factor, factor);
    }

    @Override
    public ResourceLocation getTextureLocation(TroopEntity e) {
        String identity = e.syncedCharacterId();
        if (identity == null || identity.isBlank()) identity = e.getUUID().toString();
        return (identity.hashCode() & 1) == 0 ? STEVE : ALEX;
    }
}
