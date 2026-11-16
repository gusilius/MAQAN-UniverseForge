package dev.worldforge.settle.mc;

import net.minecraft.client.model.VillagerModel;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.resources.ResourceLocation;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.texture.OverlayTexture;

/** Draws the profession clothing layer over the shared worker body using Minecraft's native villager atlas. */
final class WFWorkerProfessionLayer extends RenderLayer<WFWorker, VillagerModel<WFWorker>> {
    WFWorkerProfessionLayer(RenderLayerParent<WFWorker, VillagerModel<WFWorker>> parent) {
        super(parent);
    }

    @Override
    public void render(PoseStack pose, MultiBufferSource buffers, int packedLight, WFWorker worker,
                       float limbSwing, float limbSwingAmount, float partialTick, float ageInTicks,
                       float netHeadYaw, float headPitch) {
        String profession = professionTexture(worker.professionId());
        if (profession.isEmpty()) return;
        ResourceLocation texture = new ResourceLocation("minecraft",
                "textures/entity/villager/profession/" + profession + ".png");
        getParentModel().renderToBuffer(pose, buffers.getBuffer(RenderType.entityCutoutNoCull(texture)),
                packedLight, OverlayTexture.NO_OVERLAY, 1f, 1f, 1f, 1f);
    }

    private static String professionTexture(String id) {
        if (id == null) return "";
        return switch (id) {
            case "lumberjack", "sawyer", "hunter", "forester", "trainer" -> "fletcher";
            case "builder", "stonemason", "potter", "miner" -> "mason";
            case "rancher", "shepherd", "weaver", "stablehand" -> "shepherd";
            case "butcher", "baker", "innkeeper", "cook", "brewer" -> "butcher";
            case "fisher", "sailor", "shipwright" -> "fisherman";
            case "tanner", "porter" -> "leatherworker";
            case "jeweler", "merchant", "mint_worker" -> "cartographer";
            case "armorer", "metallurgist" -> "armorer";
            case "weaponsmith", "foundryman", "siege_engineer" -> "weaponsmith";
            case "alchemist", "herbalist", "mage", "priest", "healer" -> "cleric";
            case "beekeeper", "gardener", "miller", "salt_worker", "laborer" -> "farmer";
            case "administrator", "scribe" -> "librarian";
            default -> "";
        };
    }
}
