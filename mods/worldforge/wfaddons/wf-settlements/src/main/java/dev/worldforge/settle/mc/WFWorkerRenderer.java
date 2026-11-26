package dev.worldforge.settle.mc;

import net.minecraft.client.model.VillagerModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.resources.ResourceLocation;

/** Рабочий выглядит как обычный житель (без профессионального слоя). Загружается только на клиенте. */
public class WFWorkerRenderer extends MobRenderer<WFWorker, VillagerModel<WFWorker>> {
    private static final ResourceLocation TEXTURE = new ResourceLocation("textures/entity/villager/villager.png");

    public WFWorkerRenderer(EntityRendererProvider.Context ctx) {
        super(ctx, new VillagerModel<>(ctx.bakeLayer(ModelLayers.VILLAGER)), 0.5f);
    }

    @Override
    public ResourceLocation getTextureLocation(WFWorker entity) { return TEXTURE; }
}
