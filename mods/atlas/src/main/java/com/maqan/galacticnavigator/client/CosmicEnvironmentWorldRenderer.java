package com.maqan.galacticnavigator.client;

import com.maqan.galacticnavigator.MaqanGalacticNavigator;
import com.maqan.galacticnavigator.data.CosmicEnvironmentCatalog;
import com.maqan.galacticnavigator.data.CosmicEnvironmentCatalog.EnvironmentObject;
import com.maqan.galacticnavigator.data.CosmicEnvironmentCatalog.Kind;
import com.maqan.galacticnavigator.data.CosmicEnvironmentCatalog.Vector3;
import com.maqan.galacticnavigator.data.GalaxyCatalog;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.List;
import java.util.Locale;

/**
 * Draws MAQAN's system-space environment geometry in the actual Minecraft sky pass.
 * Coordinates are sampled from the same catalogue and orbital simulation as the
 * Navigator; no dimensions, chunks, entities or block entities are created.
 */
@Mod.EventBusSubscriber(modid = MaqanGalacticNavigator.MODID, value = Dist.CLIENT,
        bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class CosmicEnvironmentWorldRenderer {
    private static final double MAX_DISTANCE = 1.0E8;
    private CosmicEnvironmentWorldRenderer() {}

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void render(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_SKY) return;
        Minecraft mc = Minecraft.getInstance();
        Level level = mc.level;
        if (level == null || !level.isClientSide || mc.isPaused()) return;

        String systemId = systemForDimension(level.dimension().location().toString());
        if (systemId == null) return;
        Camera camera = event.getCamera();
        if (camera == null || !camera.isInitialized()) return;

        double time = level.getGameTime() + event.getPartialTick();
        CosmicMotionClock.orbitTicks(level, time);
        List<EnvironmentObject> objects = CosmicEnvironmentCatalog.snapshot(systemId, time);
        if (objects.isEmpty()) return;

        Vec3 eye = camera.getPosition();
        Vector3f left = camera.getLeftVector();
        Vector3f up = camera.getUpVector();
        BufferBuilder out = Tesselator.getInstance().getBuilder();
        out.begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);
        int rendered = 0;

        for (EnvironmentObject object : objects) {
            if (object.position() == null || !object.position().isFinite()
                    || !visibleRange(object.position(), eye)) continue;
            int color = color(object.color(), fallback(object.kind()));

            if (object.kind() == Kind.NEBULA) {
                List<CosmicEnvironmentGeometry.NebulaSample> samples =
                        CosmicEnvironmentGeometry.nebulaVolume(object);
                double size = nebulaSize(object);
                for (CosmicEnvironmentGeometry.NebulaSample sample : samples) {
                    float alpha = clamp(sample.density() * 0.30f, 0.0f, 0.42f);
                    if (alpha > 0.004f) quad(out, sample.position(), eye, left, up,
                            size * seedScale(sample.seed()), color, alpha);
                }
                if (!samples.isEmpty()) rendered++;
            } else if (object.kind() == Kind.ASTEROID_BELT) {
                List<CosmicEnvironmentGeometry.Particle> particles =
                        CosmicEnvironmentGeometry.asteroidBelt(object, time);
                for (CosmicEnvironmentGeometry.Particle particle : particles)
                    quad(out, particle.position(), eye, left, up, particle.size(),
                            color, clamp(particle.brightness() * 0.88f, 0.0f, 1.0f));
                if (!particles.isEmpty()) rendered++;
            } else if (object.kind() == Kind.COMET) {
                CosmicEnvironmentGeometry.CometGeometry comet =
                        CosmicEnvironmentGeometry.comet(object, time);
                if (comet == null) continue;
                ribbon(out, comet.dustTail(), eye, color, 0.82f);
                ribbon(out, comet.ionTail(), eye, mix(color, 0x70CFFF, 0.55f), 0.72f);
                double coma = comet.comaRadius() > 0.0 ? comet.comaRadius() : 0.8;
                quad(out, comet.nucleus(), eye, left, up, coma,
                        mix(color, 0xFFF0C0, 0.35f), 0.82f);
                rendered++;
            }
        }
        if (rendered == 0) {
            out.end();
            return;
        }
        draw(event.getPoseStack(), out);
    }

    private static String systemForDimension(String dimension) {
        String found = null;
        for (String id : GalaxyCatalog.universes()) {
            var system = GalaxyCatalog.universe(id);
            String attached = GalaxyCatalog.text(system, "dimension",
                    GalaxyCatalog.text(system, "attached_dimension_id", ""));
            if (!dimension.equals(attached)) continue;
            // Shared dimensions are ambiguous coordinate frames: never overlay systems.
            if (found != null) return null;
            found = id;
        }
        return found;
    }

    private static boolean visibleRange(Vector3 p, Vec3 eye) {
        double x = p.x() - eye.x, y = p.y() - eye.y, z = p.z() - eye.z;
        return Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z)
                && x*x + y*y + z*z <= MAX_DISTANCE * MAX_DISTANCE;
    }

    private static double nebulaSize(EnvironmentObject o) {
        double extent = o.volumeExtent() != null && o.volumeExtent().isPositiveExtent()
                ? Math.max(o.volumeExtent().x(), Math.max(o.volumeExtent().y(), o.volumeExtent().z()))
                : Double.isFinite(o.volumeRadius()) && o.volumeRadius() > 0 ? o.volumeRadius() * 2 : 0;
        double scale = Double.isFinite(o.volumeRenderScale())
                ? Math.max(0.1, Math.min(8.0, o.volumeRenderScale())) : 1.0;
        return Math.max(0.35, Math.min(32.0, extent * scale * 0.009));
    }

    private static double seedScale(int seed) {
        long v = seed * 0x9E3779B97F4A7C15L;
        v ^= v >>> 30;
        return 0.70 + ((v >>> 40) & 65535) / 65535.0 * 0.65;
    }

    private static void quad(BufferBuilder b, Vector3 p, Vec3 eye, Vector3f left,
                             Vector3f up, double size, int rgb, float alpha) {
        if (p == null || !p.isFinite() || !Double.isFinite(size) || size <= 0 || alpha <= 0) return;
        double x=p.x()-eye.x, y=p.y()-eye.y, z=p.z()-eye.z;
        double lx=left.x()*size, ly=left.y()*size, lz=left.z()*size;
        double ux=up.x()*size, uy=up.y()*size, uz=up.z()*size;
        vertex(b,x-lx-ux,y-ly-uy,z-lz-uz,rgb,alpha);
        vertex(b,x+lx-ux,y+ly-uy,z+lz-uz,rgb,alpha);
        vertex(b,x+lx+ux,y+ly+uy,z+lz+uz,rgb,alpha);
        vertex(b,x-lx-ux,y-ly-uy,z-lz-uz,rgb,alpha);
        vertex(b,x+lx+ux,y+ly+uy,z+lz+uz,rgb,alpha);
        vertex(b,x-lx+ux,y-ly+uy,z-lz+uz,rgb,alpha);
    }

    private static void ribbon(BufferBuilder b, List<CosmicEnvironmentGeometry.CometVertex> v,
                               Vec3 eye, int rgb, float strength) {
        if (v == null || v.size() < 4) return;
        for (int i=0; i<v.size()/2-1; i++) {
            var a=v.get(i*2); var a2=v.get(i*2+1);
            var c=v.get((i+1)*2); var c2=v.get((i+1)*2+1);
            float aa=clamp(a.opacity()*strength,0,1), ac=clamp(c.opacity()*strength,0,1);
            vertex(b,a.position(),eye,rgb,aa); vertex(b,a2.position(),eye,rgb,aa);
            vertex(b,c2.position(),eye,rgb,ac); vertex(b,a.position(),eye,rgb,aa);
            vertex(b,c2.position(),eye,rgb,ac); vertex(b,c.position(),eye,rgb,ac);
        }
    }

    private static void vertex(BufferBuilder b, Vector3 p, Vec3 eye, int rgb, float alpha) {
        vertex(b,p.x()-eye.x,p.y()-eye.y,p.z()-eye.z,rgb,alpha);
    }
    private static void vertex(BufferBuilder b,double x,double y,double z,int rgb,float alpha) {
        if (!Double.isFinite(x)||!Double.isFinite(y)||!Double.isFinite(z)) return;
        b.vertex((float)x,(float)y,(float)z)
                .color((rgb>>16)&255,(rgb>>8)&255,rgb&255,
                        Math.round(clamp(alpha,0,1)*255)).endVertex();
    }

    private static void draw(PoseStack stack, BufferBuilder builder) {
        Matrix4f pose = new Matrix4f(stack.last().pose());
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.disableCull();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        var modelView = RenderSystem.getModelViewStack();
        modelView.pushPose();
        modelView.mulPoseMatrix(pose);
        RenderSystem.applyModelViewMatrix();
        try {
            BufferUploader.drawWithShader(builder.end());
        } finally {
            modelView.popPose();
            RenderSystem.applyModelViewMatrix();
            RenderSystem.depthMask(true);
            RenderSystem.enableDepthTest();
            RenderSystem.enableCull();
            RenderSystem.disableBlend();
            RenderSystem.setShaderColor(1,1,1,1);
        }
    }

    private static int fallback(Kind k) {
        return switch (k) {
            case NEBULA -> 0x8D7CFF;
            case ASTEROID_BELT -> 0xAEB8C5;
            case COMET -> 0xE8B56B;
            default -> 0xFFFFFF;
        };
    }
    private static int color(String value,int fallback) {
        if (value==null || value.isBlank()) return fallback;
        String s=value.trim().toLowerCase(Locale.ROOT);
        if(s.startsWith("#")) s=s.substring(1);
        else if(s.startsWith("0x")) s=s.substring(2);
        try { if(s.length()==6 || s.length()==8) return (int)Long.parseLong(s,16)&0xFFFFFF; }
        catch(NumberFormatException ignored) {}
        return fallback;
    }
    private static int mix(int a,int b,float t) {
        t=clamp(t,0,1);
        int r=Math.round(((a>>16)&255)*(1-t)+((b>>16)&255)*t);
        int g=Math.round(((a>>8)&255)*(1-t)+((b>>8)&255)*t);
        int bl=Math.round((a&255)*(1-t)+(b&255)*t);
        return (r<<16)|(g<<8)|bl;
    }
    private static float clamp(float x,float lo,float hi) { return Math.max(lo,Math.min(hi,x)); }
}
