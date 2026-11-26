package com.maqan.galacticnavigator.client;

import com.google.gson.JsonObject;
import com.maqan.galacticnavigator.data.GalaxyCatalog;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.resources.ResourceLocation;

/**
 * Cosmic Horizons GUI planet renderer.
 *
 * Cosmic Horizons does not render its catalogue planets as spheres. It maps
 * the planet's texture_id atlas onto the six faces of a rotating cube. The
 * face order, UV coordinates and face tints below mirror TexturedcubeProcedure
 * from Cosmic Horizons 0.0.7.3.
 */
public final class CosmicBodyGuiRenderer {
    private static double lastAnimationTime = Double.NaN;
    private static double accumulatedAnimationTicks;
    private static net.minecraft.world.level.Level lastAnimationLevel;

    private CosmicBodyGuiRenderer() {}

    private static synchronized double animationTicks() {
        Minecraft minecraft = Minecraft.getInstance();
        net.minecraft.world.level.Level currentLevel = minecraft.level;
        if (currentLevel == null) return 0.0;
        return CosmicMotionClock.rotationTicks(currentLevel,
                currentLevel.getGameTime() + minecraft.getFrameTime());
    }

    public static boolean render(GuiGraphics graphics, int centerX, int centerY,
                                 int radius, JsonObject body) {
        return render(graphics, centerX, centerY, radius, body, 0.0f);
    }

    /** Depth-aware variant used by the close-up scene, matching Cosmic Horizons' GUI depth. */
    public static boolean render(GuiGraphics graphics, int centerX, int centerY,
                                 int radius, JsonObject body, float depth) {
        return render(graphics, centerX, centerY, radius, body, depth, 0.0f, 0.0f);
    }

    /** Adds user-controlled yaw/pitch offsets without changing source JSON rotation. */
    public static boolean render(GuiGraphics graphics, int centerX, int centerY,
                                 int radius, JsonObject body, float depth,
                                 float yawOffset, float pitchOffset) {
        if (radius < 3 || body == null) return false;
        // Cosmic Horizons' documented GUI schema uses texture_id. Accept common
        // resource-pack aliases as well, without changing the source catalogue.
        String textureName = "";
        for (String key : new String[]{"texture_id", "texture", "texture_location", "planet_texture"}) {
            if (!body.has(key) || body.get(key).isJsonNull()) continue;
            try {
                textureName = body.get(key).getAsString().trim();
                if (!textureName.isEmpty()) break;
            } catch (RuntimeException ignored) { }
        }
        if (textureName.isEmpty()) return false;

        ResourceLocation texture = textureLocation(textureName);
        if (texture == null || Minecraft.getInstance().getResourceManager()
                .getResource(texture).isEmpty()) return false;

        PoseStack pose = graphics.pose();
        pose.pushPose();
        pose.translate(centerX, centerY, depth);

        double ticks = animationTicks();
        // Match Cosmic Horizons' original renderShape transform order.
        pose.mulPose(com.mojang.math.Axis.YP.rotation(
                animatedAngle(body, "yaw", "yaw_speed", ticks) + yawOffset));
        pose.mulPose(com.mojang.math.Axis.XP.rotation(
                animatedAngle(body, "pitch", "pitch_speed", ticks) + pitchOffset));
        pose.mulPose(com.mojang.math.Axis.ZP.rotation(
                animatedAngle(body, "roll", "roll_speed", ticks) + axialTiltRadians(body)));
        pose.scale(radius * 2.0f, radius * 2.0f, radius * 2.0f);

        BufferBuilder builder = beginTexturedMesh();
        renderTexturedCube(builder);
        drawTexturedMesh(graphics, texture, builder, pose, true);

        pose.popPose();
        return true;
    }

    private static BufferBuilder beginTexturedMesh() {
        BufferBuilder builder = Tesselator.getInstance().getBuilder();
        builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        return builder;
    }

    /**
     * Cosmic Horizons draws POSITION_TEX_COLOR vertex buffers directly.
     * Match its vertex layout and position/texture/color shader exactly.
     */
    private static void drawTexturedMesh(GuiGraphics graphics, ResourceLocation texture,
                                         BufferBuilder builder, PoseStack guiPose,
                                         boolean writeDepth) {
        graphics.flush();

        // The world renderer may leave depth values behind in Minecraft's GUI
        // framebuffer. The navigator clips each workspace to its own scissor
        // rectangle, so clear only that panel's depth region before drawing
        // the model. Otherwise unrelated world depth can cut holes through
        // rotating faces even when the cube itself is correctly constructed.
        RenderSystem.clear(256, Minecraft.ON_OSX);

        // Cosmic Horizons does not bake the GUI PoseStack into every vertex.
        // It multiplies that matrix into RenderSystem's model-view stack and
        // draws the untouched POSITION_TEX_COLOR mesh. Doing the same avoids
        // mixing screen-space vertex coordinates with a live GUI model-view
        // matrix, which can produce depth/clipping artefacts while rotating.
        PoseStack modelView = RenderSystem.getModelViewStack();
        modelView.pushPose();
        modelView.mulPoseMatrix(guiPose.last().pose());
        RenderSystem.applyModelViewMatrix();

        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(writeDepth);
        RenderSystem.disableCull();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
        RenderSystem.setShaderTexture(0, texture);
        RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
        try {
            BufferUploader.drawWithShader(builder.end());
        } finally {
            RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
            RenderSystem.defaultBlendFunc();
            RenderSystem.disableBlend();
            RenderSystem.enableCull();
            RenderSystem.enableDepthTest();
            RenderSystem.depthMask(true);
            // Do not let this preview's depth values occlude later GUI panels.
            RenderSystem.clear(256, Minecraft.ON_OSX);
            modelView.popPose();
            RenderSystem.applyModelViewMatrix();
        }
    }

    /**
     * Reproduces the six quads and UV layout from Cosmic Horizons'
     * TexturedcubeProcedure. The atlas is a four-by-two net of square faces:
     * bottom/top occupy the upper row and the four side faces occupy the
     * lower row. UVs intentionally use only the atlas region used upstream.
     */
    private static void renderTexturedCube(BufferBuilder out) {
        // Bottom face (Y-), atlas tile x=0.25..0.375 / y=0..0.25.
        cubeFace(out, 0xFFA0A0A0,
                0.5f, -0.5f, -0.5f, 0.375f, 0.0f,
                0.5f, -0.5f,  0.5f, 0.375f, 0.25f,
               -0.5f, -0.5f,  0.5f, 0.25f,  0.25f,
               -0.5f, -0.5f, -0.5f, 0.25f,  0.0f);

        // Top face (Y+), atlas tile x=0.125..0.25 / y=0..0.25.
        cubeFace(out, 0xFFFFFFFF,
               -0.5f,  0.5f, -0.5f, 0.125f, 0.0f,
               -0.5f,  0.5f,  0.5f, 0.125f, 0.25f,
                0.5f,  0.5f,  0.5f, 0.25f,  0.25f,
                0.5f,  0.5f, -0.5f, 0.25f,  0.0f);

        // Front face (Z+), atlas tile x=0.125..0.25 / y=0.25..0.5.
        cubeFace(out, 0xFFE0E0E0,
               -0.5f,  0.5f, 0.5f, 0.125f, 0.25f,
               -0.5f, -0.5f, 0.5f, 0.125f, 0.5f,
                0.5f, -0.5f, 0.5f, 0.25f,  0.5f,
                0.5f,  0.5f, 0.5f, 0.25f,  0.25f);

        // Right face (X+), atlas tile x=0.25..0.375 / y=0.25..0.5.
        cubeFace(out, 0xFFC0C0C0,
                0.5f,  0.5f,  0.5f, 0.25f,  0.25f,
                0.5f, -0.5f,  0.5f, 0.25f,  0.5f,
                0.5f, -0.5f, -0.5f, 0.375f, 0.5f,
                0.5f,  0.5f, -0.5f, 0.375f, 0.25f);

        // Back face (Z-), atlas tile x=0.375..0.5 / y=0.25..0.5.
        cubeFace(out, 0xFFE0E0E0,
                0.5f,  0.5f, -0.5f, 0.375f, 0.25f,
                0.5f, -0.5f, -0.5f, 0.375f, 0.5f,
               -0.5f, -0.5f, -0.5f, 0.5f,   0.5f,
               -0.5f,  0.5f, -0.5f, 0.5f,   0.25f);

        // Left face (X-), atlas tile x=0..0.125 / y=0.25..0.5.
        cubeFace(out, 0xFFC0C0C0,
               -0.5f,  0.5f, -0.5f, 0.0f,   0.25f,
               -0.5f, -0.5f, -0.5f, 0.0f,   0.5f,
               -0.5f, -0.5f,  0.5f, 0.125f, 0.5f,
               -0.5f,  0.5f,  0.5f, 0.125f, 0.25f);
    }

    private static void cubeFace(BufferBuilder out,
                                 int color,
                                 float x0, float y0, float z0, float u0, float v0,
                                 float x1, float y1, float z1, float u1, float v1,
                                 float x2, float y2, float z2, float u2, float v2,
                                 float x3, float y3, float z3, float u3, float v3) {
        cubeVertex(out, color, x0, y0, z0, u0, v0);
        cubeVertex(out, color, x1, y1, z1, u1, v1);
        cubeVertex(out, color, x2, y2, z2, u2, v2);
        cubeVertex(out, color, x3, y3, z3, u3, v3);
    }

    private static void cubeVertex(BufferBuilder out,
                                   int color, float x, float y, float z, float u, float v) {
        out.vertex(x, y, z)
                .uv(u, v)
                .color((color >> 16) & 255, (color >> 8) & 255, color & 255, (color >>> 24) & 255)
                .endVertex();
    }

    /**
     * Render the native Cosmic Horizons ring mesh from ring_data.
     * The source uses an XZ-plane square annulus with four UV-mapped strips,
     * rotated by the same yaw/pitch/roll as the parent planet cube.
     */
    public static void renderRings(GuiGraphics graphics, int centerX, int centerY,
                                   int planetRadius, JsonObject body) {
        renderRings(graphics, centerX, centerY, planetRadius, body, 0.0f);
    }

    /** Depth-aware variant paired with the close-up planet cube. */
    public static void renderRings(GuiGraphics graphics, int centerX, int centerY,
                                   int planetRadius, JsonObject body, float depth) {
        renderRings(graphics, centerX, centerY, planetRadius, body, depth, 0.0f, 0.0f);
    }

    /** Keep the ring plane aligned while the user rotates the planet. */
    public static void renderRings(GuiGraphics graphics, int centerX, int centerY,
                                   int planetRadius, JsonObject body, float depth,
                                   float yawOffset, float pitchOffset) {
        if (body == null || !body.has("ring_data") || !body.get("ring_data").isJsonObject()) return;
        if (body.has("ringed") && !bool(body, "ringed")) return;

        JsonObject rings = body.getAsJsonObject("ring_data");
        if (rings.size() == 0) return;
        double bodyScale = number(body, "scale", 0.0);
        if (!Double.isFinite(bodyScale) || bodyScale <= 0.0) return;

        double ticks = animationTicks();
        Minecraft minecraft = Minecraft.getInstance();
        PoseStack pose = graphics.pose();
        pose.pushPose();
        pose.translate(centerX, centerY, depth);
        pose.mulPose(com.mojang.math.Axis.YP.rotation(
                animatedAngle(body, "yaw", "yaw_speed", ticks) + yawOffset));
        pose.mulPose(com.mojang.math.Axis.XP.rotation(
                animatedAngle(body, "pitch", "pitch_speed", ticks) + pitchOffset));
        pose.mulPose(com.mojang.math.Axis.ZP.rotation(
                animatedAngle(body, "roll", "roll_speed", ticks) + axialTiltRadians(body)));

        for (java.util.Map.Entry<String, com.google.gson.JsonElement> entry : rings.entrySet()) {
            if (!entry.getValue().isJsonObject()) continue;
            JsonObject ring = entry.getValue().getAsJsonObject();
            String id = string(ring, "texture_id");
            double sourceRingScale = number(ring, "scale_radius", 0.0);
            if (id.isEmpty() || !Double.isFinite(sourceRingScale) || sourceRingScale <= 0.0) continue;

            ResourceLocation texture = textureLocation(id);
            if (texture == null || minecraft.getResourceManager().getResource(texture).isEmpty()) continue;

            float ringScale = (float)(2.0 * planetRadius * sourceRingScale / bodyScale);
            if (!Float.isFinite(ringScale) || ringScale <= 0.0f) continue;

            pose.pushPose();
            pose.scale(ringScale, ringScale, ringScale);
            BufferBuilder builder = beginTexturedMesh();
            drawCosmicHorizonsRingMesh(builder);
            drawTexturedMesh(graphics, texture, builder, pose, false);
            pose.popPose();
        }
        pose.popPose();
    }

    /** Four unique strips from Cosmic Horizons RingsProcedure (duplicate reverse faces omitted). */
    private static void drawCosmicHorizonsRingMesh(BufferBuilder out) {
        // Negative-X side.
        ringQuad(out,
                -0.5f, 0.0f, -0.5f, 1.0f, 0.0f,
                -0.25f, 0.0f, -0.25f, 0.75f, 1.0f,
                -0.25f, 0.0f,  0.25f, 0.25f, 1.0f,
                -0.5f, 0.0f,  0.5f, 0.0f, 0.0f);
        // Positive-X side.
        ringQuad(out,
                 0.25f, 0.0f, -0.25f, 0.25f, 1.0f,
                 0.5f, 0.0f, -0.5f, 0.0f, 0.0f,
                 0.5f, 0.0f,  0.5f, 1.0f, 0.0f,
                 0.25f, 0.0f,  0.25f, 0.75f, 1.0f);
        // Negative-Z side.
        ringQuad(out,
                -0.5f, 0.0f, -0.5f, 0.0f, 0.0f,
                 0.5f, 0.0f, -0.5f, 1.0f, 0.0f,
                 0.25f, 0.0f, -0.25f, 0.75f, 1.0f,
                -0.25f, 0.0f, -0.25f, 0.25f, 1.0f);
        // Positive-Z side.
        ringQuad(out,
                -0.5f, 0.0f,  0.5f, 1.0f, 0.0f,
                 0.5f, 0.0f,  0.5f, 0.0f, 0.0f,
                 0.25f, 0.0f,  0.25f, 0.25f, 1.0f,
                -0.25f, 0.0f,  0.25f, 0.75f, 1.0f);
    }

    private static void ringQuad(BufferBuilder out,
                                 float x0, float y0, float z0, float u0, float v0,
                                 float x1, float y1, float z1, float u1, float v1,
                                 float x2, float y2, float z2, float u2, float v2,
                                 float x3, float y3, float z3, float u3, float v3) {
        ringVertex(out, x0, y0, z0, u0, v0);
        ringVertex(out, x1, y1, z1, u1, v1);
        ringVertex(out, x2, y2, z2, u2, v2);
        ringVertex(out, x3, y3, z3, u3, v3);
    }

    private static void ringVertex(BufferBuilder out,
                                   float x, float y, float z, float u, float v) {
        out.vertex(x, y, z).uv(u, v).color(255, 255, 255, 255).endVertex();
    }

    private static double number(JsonObject object, String key, double fallback) {
        try { return object.has(key) ? object.get(key).getAsDouble() : fallback; }
        catch (RuntimeException ignored) { return fallback; }
    }

    private static String string(JsonObject object, String key) {
        try { return object.has(key) ? object.get(key).getAsString() : ""; }
        catch (RuntimeException ignored) { return ""; }
    }

    private static boolean bool(JsonObject object, String key) {
        try { return object.has(key) && object.get(key).getAsBoolean(); }
        catch (RuntimeException ignored) { return false; }
    }

    private static float animatedAngle(JsonObject body, String angleKey, String speedKey, double ticks) {
        double base = number(body, angleKey, 0.0);
        CosmicOrbitSettings.Config settings = CosmicOrbitSettings.get();
        String systemId = GalaxyCatalog.text(body, "system_id", "");
        String bodyId = GalaxyCatalog.text(body, "catalog_id", GalaxyCatalog.text(body, "id", ""));
        CosmicOrbitSettings.BodyOverride override = settings.findBody(systemId, bodyId);
        if (!settings.enabled || !settings.axialRotation || (override != null && !override.rotationEnabled))
            return (float)base;
        double speed = number(body, speedKey, Double.NaN);
        // Cosmic Horizons passes these values straight to Axis.rotation(),
        // whose input is radians. Use the same per-body/global fallback period as in-game.
        if (!Double.isFinite(speed) || ("yaw_speed".equals(speedKey) && Math.abs(speed) < 1.0E-6)) {
            double period = override != null && override.axialRotationPeriodTicks > 0.0
                    ? override.axialRotationPeriodTicks
                    : number(body, "axial_rotation_period_ticks",
                    number(body, "rotation_period_ticks", settings.defaultAxialRotationTicks));
            speed = "yaw_speed".equals(speedKey) ? Math.PI * 2.0 / Math.max(1.0, period) : 0.0;
        }
        double angle = base + ticks * speed;
        return (float)Math.IEEEremainder(angle, Math.PI * 2.0);
    }

    private static float axialTiltRadians(JsonObject body) {
        if (!CosmicOrbitSettings.get().enabled) return 0.0f;
        String systemId = GalaxyCatalog.text(body, "system_id", "");
        String bodyId = GalaxyCatalog.text(body, "catalog_id", GalaxyCatalog.text(body, "id", ""));
        CosmicOrbitSettings.BodyOverride override = CosmicOrbitSettings.get().findBody(systemId, bodyId);
        double tilt = override != null && override.useAxialTiltOverride
                ? override.axialTiltDegrees : number(body, "axial_tilt_degrees",
                number(body, "axial_tilt", 0.0));
        return (float)Math.toRadians(tilt);
    }

    private static ResourceLocation textureLocation(String value) {
        try {
            String path = value.trim();
            if (path.contains(":")) return ResourceLocation.tryParse(path);
            if (!path.startsWith("textures/")) path = "textures/" + path;
            if (!path.endsWith(".png")) path += ".png";
            return ResourceLocation.fromNamespaceAndPath("cosmos", path);
        } catch (RuntimeException ignored) { return null; }
    }
}
