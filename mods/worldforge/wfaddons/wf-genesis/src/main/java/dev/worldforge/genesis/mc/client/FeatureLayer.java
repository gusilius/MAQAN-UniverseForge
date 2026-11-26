package dev.worldforge.genesis.mc.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;

import java.util.Set;

/**
 * Облик игрока: цвет кожи (полупрозрачная раскраска поверх скина), причёска, борода, уши, рога, клыки, хвост, светящиеся глаза, раскраска.
 * Всё собрано из цветных кубиков на голове и теле, поэтому работает с любым скином и без ресурс-пака.
 */
public class FeatureLayer extends RenderLayer<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> {
    private static final ResourceLocation WHITE = new ResourceLocation("minecraft", "textures/misc/white.png");
    private static ModelPart root;

    public FeatureLayer(RenderLayerParent<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> parent) { super(parent); }

    private static ModelPart root() {
        if (root == null) {
            MeshDefinition mesh = new MeshDefinition();
            PartDefinition r = mesh.getRoot();
            // волосы
            box(r, "hair_cap", -4.5f, -8.5f, -4.5f, 9, 3, 9, PartPose.ZERO);
            box(r, "hair_cap_big", -5f, -9f, -5f, 10, 4, 10, PartPose.ZERO);
            box(r, "hair_back_short", -4.5f, -8.5f, 3f, 9, 6, 1.5f, PartPose.ZERO);
            box(r, "hair_back_long", -4.5f, -8.5f, 3f, 9, 14, 1.5f, PartPose.ZERO);
            box(r, "hair_sides", -4.75f, -8.5f, -4.5f, 9.5f, 6, 3, PartPose.ZERO);
            box(r, "hair_braid", -1f, -5f, 3.2f, 2, 17, 1.6f, PartPose.ZERO);
            box(r, "hair_pony", -1.5f, -7f, 4f, 3, 11, 2, PartPose.ZERO);
            box(r, "hair_bun", -2f, -11.5f, -1f, 4, 3, 4, PartPose.ZERO);
            box(r, "hair_mohawk", -1f, -10.5f, -4.5f, 2, 3, 9, PartPose.ZERO);
            box(r, "hair_strip", -2f, -9f, -4.5f, 4, 1.5f, 9, PartPose.ZERO);
            box(r, "hair_mane", -5f, -9f, -1f, 10, 8, 6, PartPose.ZERO);
            // борода
            box(r, "beard_long", -3.5f, -2f, -4.7f, 7, 6, 1, PartPose.ZERO);
            box(r, "beard_braid", -1.5f, -2f, -4.7f, 3, 10, 1.3f, PartPose.ZERO);
            // уши
            box(r, "ear_l", 0, 0, 0, 3, 1.5f, 1, PartPose.offsetAndRotation(4f, -6f, -0.5f, 0, 0, -0.35f));
            box(r, "ear_r", -3f, 0, 0, 3, 1.5f, 1, PartPose.offsetAndRotation(-4f, -6f, -0.5f, 0, 0, 0.35f));
            box(r, "ear_long_l", 0, 0, 0, 5, 1.5f, 1, PartPose.offsetAndRotation(4f, -6f, -0.5f, 0, 0, -0.2f));
            box(r, "ear_long_r", -5f, 0, 0, 5, 1.5f, 1, PartPose.offsetAndRotation(-4f, -6f, -0.5f, 0, 0, 0.2f));
            box(r, "ear_beast_l", 0.5f, -11f, -1.5f, 3, 3, 1.5f, PartPose.ZERO);
            box(r, "ear_beast_r", -3.5f, -11f, -1.5f, 3, 3, 1.5f, PartPose.ZERO);
            box(r, "fin_l", 4f, -6f, -1f, 2, 4, 2, PartPose.ZERO);
            box(r, "fin_r", -6f, -6f, -1f, 2, 4, 2, PartPose.ZERO);
            // рога, клыки
            box(r, "horn_s_l", 1.5f, -10.5f, -2f, 1.5f, 2.5f, 1.5f, PartPose.ZERO);
            box(r, "horn_s_r", -3f, -10.5f, -2f, 1.5f, 2.5f, 1.5f, PartPose.ZERO);
            box(r, "horn_c_l", 0, 0, 0, 1.5f, 5, 1.5f, PartPose.offsetAndRotation(3f, -8f, -2f, 0, 0, -0.5f));
            box(r, "horn_c_r", -1.5f, 0, 0, 1.5f, 5, 1.5f, PartPose.offsetAndRotation(-3f, -8f, -2f, 0, 0, 0.5f));
            box(r, "tusk_l", 1.5f, -2.8f, -4.8f, 1, 2, 1, PartPose.ZERO);
            box(r, "tusk_r", -2.5f, -2.8f, -4.8f, 1, 2, 1, PartPose.ZERO);
            box(r, "fang_l", 1f, -1.8f, -4.6f, 0.7f, 1, 0.7f, PartPose.ZERO);
            box(r, "fang_r", -1.7f, -1.8f, -4.6f, 0.7f, 1, 0.7f, PartPose.ZERO);
            // глаза, раскраска, веснушки, руны
            box(r, "eye_l", 1f, -4.6f, -4.15f, 2, 1, 0.3f, PartPose.ZERO);
            box(r, "eye_r", -3f, -4.6f, -4.15f, 2, 1, 0.3f, PartPose.ZERO);
            box(r, "paint", -4.1f, -5.6f, -4.1f, 8.2f, 1, 0.3f, PartPose.ZERO);
            box(r, "paint_cheek_l", 2f, -3.4f, -4.1f, 2, 2, 0.3f, PartPose.ZERO);
            box(r, "paint_cheek_r", -4f, -3.4f, -4.1f, 2, 2, 0.3f, PartPose.ZERO);
            box(r, "freckle_l", 1.5f, -3.4f, -4.1f, 2, 0.8f, 0.3f, PartPose.ZERO);
            box(r, "freckle_r", -3.5f, -3.4f, -4.1f, 2, 0.8f, 0.3f, PartPose.ZERO);
            box(r, "rune", -0.5f, -7.4f, -4.1f, 1, 2, 0.3f, PartPose.ZERO);
            // хвосты (в системе тела)
            box(r, "tail1", -1f, 9f, 2f, 2, 2, 2.5f, PartPose.ZERO);
            box(r, "tail2", -1f, 10f, 4.3f, 2, 2, 2.5f, PartPose.ZERO);
            box(r, "tail3", -0.75f, 11f, 6.6f, 1.5f, 1.5f, 2.5f, PartPose.ZERO);
            box(r, "fox_tail", -2f, 8f, 2f, 4, 4, 7, PartPose.ZERO);
            box(r, "fox_tip", -2.1f, 8f, 7.5f, 4.2f, 4.2f, 2, PartPose.ZERO);
            root = LayerDefinition.create(mesh, 64, 64).bakeRoot();
        }
        return root;
    }

    private static void box(PartDefinition r, String n, float x, float y, float z, float w, float h, float d, PartPose pose) {
        r.addOrReplaceChild(n, CubeListBuilder.create().texOffs(0, 0).addBox(x, y, z, w, h, d), pose);
    }

    private void draw(PoseStack ps, VertexConsumer vc, int light, String part, int rgb) {
        root().getChild(part).render(ps, vc, light, OverlayTexture.NO_OVERLAY, ((rgb >> 16) & 255) / 255f, ((rgb >> 8) & 255) / 255f, (rgb & 255) / 255f, 1f);
    }

    @Override
    public void render(PoseStack ps, MultiBufferSource buf, int light, AbstractClientPlayer p, float limbSwing, float limbSwingAmount, float partial, float age, float yaw, float pitch) {
        ClientLooks.Look l = ClientLooks.get(p.getUUID());
        if (l == null || p.isInvisible()) return;
        PlayerModel<AbstractClientPlayer> m = getParentModel();
        int skin = l.color("skin", 0xf1c9a5), hair = l.color("hair", 0x5a3a1e), eyes = l.color("eyes", 0x4b2e1a);
        Set<String> f = l.features();
        // окраска кожи: тот же скин, подкрашенный и полупрозрачный
        if (skin != 0xf1c9a5) {
            VertexConsumer tint = buf.getBuffer(RenderType.entityTranslucent(p.getSkinTextureLocation()));
            m.renderToBuffer(ps, tint, light, LivingEntityRenderer.getOverlayCoords(p, 0f), ((skin >> 16) & 255) / 255f, ((skin >> 8) & 255) / 255f, (skin & 255) / 255f, 0.55f);
        }
        VertexConsumer vc = buf.getBuffer(RenderType.entityCutoutNoCull(WHITE));
        ps.pushPose();
        m.head.translateAndRotate(ps);
        String style = l.look().getOrDefault("hair_style", "short");
        switch (style) {
            case "short" -> { draw(ps, vc, light, "hair_cap", hair); draw(ps, vc, light, "hair_back_short", hair); }
            case "long" -> { draw(ps, vc, light, "hair_cap", hair); draw(ps, vc, light, "hair_back_long", hair); draw(ps, vc, light, "hair_sides", hair); }
            case "braid" -> { draw(ps, vc, light, "hair_cap", hair); draw(ps, vc, light, "hair_back_short", hair); draw(ps, vc, light, "hair_braid", hair); }
            case "ponytail" -> { draw(ps, vc, light, "hair_cap", hair); draw(ps, vc, light, "hair_back_short", hair); draw(ps, vc, light, "hair_pony", hair); }
            case "bun" -> { draw(ps, vc, light, "hair_cap", hair); draw(ps, vc, light, "hair_back_short", hair); draw(ps, vc, light, "hair_bun", hair); }
            case "mohawk" -> draw(ps, vc, light, "hair_mohawk", hair);
            case "curly" -> { draw(ps, vc, light, "hair_cap_big", hair); draw(ps, vc, light, "hair_back_short", hair); draw(ps, vc, light, "hair_sides", hair); }
            case "shaved_sides" -> { draw(ps, vc, light, "hair_strip", hair); }
            case "mane" -> { draw(ps, vc, light, "hair_cap_big", hair); draw(ps, vc, light, "hair_mane", hair); draw(ps, vc, light, "hair_back_long", hair); }
            default -> { }
        }
        if (f.contains("long_beard")) draw(ps, vc, light, "beard_long", hair);
        if (f.contains("braided_beard")) draw(ps, vc, light, "beard_braid", hair);
        if (f.contains("pointed_ears")) { draw(ps, vc, light, "ear_l", skin); draw(ps, vc, light, "ear_r", skin); }
        if (f.contains("long_ears")) { draw(ps, vc, light, "ear_long_l", skin); draw(ps, vc, light, "ear_long_r", skin); }
        if (f.contains("animal_ears")) { draw(ps, vc, light, "ear_beast_l", hair); draw(ps, vc, light, "ear_beast_r", hair); }
        if (f.contains("fins")) { draw(ps, vc, light, "fin_l", skin); draw(ps, vc, light, "fin_r", skin); }
        if (f.contains("short_horns")) { draw(ps, vc, light, "horn_s_l", 0xe8e0c8); draw(ps, vc, light, "horn_s_r", 0xe8e0c8); }
        if (f.contains("curved_horns")) { draw(ps, vc, light, "horn_c_l", 0xd8cfae); draw(ps, vc, light, "horn_c_r", 0xd8cfae); }
        if (f.contains("tusks")) { draw(ps, vc, light, "tusk_l", 0xf2eed8); draw(ps, vc, light, "tusk_r", 0xf2eed8); }
        if (f.contains("fangs")) { draw(ps, vc, light, "fang_l", 0xf2eed8); draw(ps, vc, light, "fang_r", 0xf2eed8); }
        if (f.contains("tribal_paint")) { draw(ps, vc, light, "paint", 0xa52a2a); draw(ps, vc, light, "paint_cheek_l", 0xa52a2a); draw(ps, vc, light, "paint_cheek_r", 0xa52a2a); }
        if (f.contains("tattoos")) { draw(ps, vc, light, "paint_cheek_l", 0x2a3f6b); draw(ps, vc, light, "paint_cheek_r", 0x2a3f6b); }
        if (f.contains("freckles")) { draw(ps, vc, light, "freckle_l", 0x9a5a32); draw(ps, vc, light, "freckle_r", 0x9a5a32); }
        if (f.contains("runes")) draw(ps, vc, LightTexture.FULL_BRIGHT, "rune", 0x58c8ff);
        if (f.contains("glow_eyes")) { draw(ps, vc, LightTexture.FULL_BRIGHT, "eye_l", eyes); draw(ps, vc, LightTexture.FULL_BRIGHT, "eye_r", eyes); }
        ps.popPose();
        if (f.contains("tail") || f.contains("fox_tail")) {
            ps.pushPose();
            m.body.translateAndRotate(ps);
            if (f.contains("fox_tail")) { draw(ps, vc, light, "fox_tail", hair); draw(ps, vc, light, "fox_tip", 0xf4f1e6); }
            else { draw(ps, vc, light, "tail1", skin); draw(ps, vc, light, "tail2", skin); draw(ps, vc, light, "tail3", skin); }
            ps.popPose();
        }
    }
}
