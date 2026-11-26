package com.maqan.galacticnavigator.client;

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

import java.util.ArrayList;
import java.util.List;

/**
 * Antialiased screen-space geometry for the galaxy atlas and orbital views.
 *
 * Lines are submitted as sub-pixel triangle meshes with a transparent fringe;
 * they no longer use integer Bresenham pixels, which made large ellipses and
 * diagonal territory edges look stair-stepped.
 */
public final class SolarSystemRenderer {
    private static final float EDGE_SOFTNESS = 1.15f;
    private SolarSystemRenderer() {}

    public static void orbit(GuiGraphics g, int cx, int cy, double rx, double ry, int color) {
        orbit(g, cx, cy, rx, ry, color, NavigatorPreferences.orbitThickness());
    }

    public static void orbit(GuiGraphics g, int cx, int cy, double rx, double ry,
                             int color, int thickness) {
        if (!NavigatorPreferences.orbitsVisible() || !Double.isFinite(rx) || !Double.isFinite(ry)
                || rx < 2 || ry < 2 || thickness <= 0) return;
        // Use geometry-adaptive tessellation: tiny orbital glyphs do not need
        // 96 vertices, while large map-scale ellipses remain smooth without
        // allocating hundreds of vertices for every body on every frame.
        int segments = Math.max(24, Math.min(256,
                (int)Math.ceil(Math.PI * 2.0 * Math.max(rx, ry) / 2.0)));
        double[] xs = new double[segments];
        double[] ys = new double[segments];
        for (int i = 0; i < segments; i++) {
            double angle = Math.PI * 2.0 * i / segments;
            xs[i] = cx + Math.cos(angle) * rx;
            ys[i] = cy + Math.sin(angle) * ry;
        }
        polyline(g, xs, ys, color, Math.max(0.8f, thickness), true);
    }

    public static void ring(GuiGraphics g, int cx, int cy, double rx, double ry,
                            double rotation, int color) {
        if (!Double.isFinite(rx) || !Double.isFinite(ry) || rx < 3 || ry < 1) return;
        int segments = Math.max(24, Math.min(256,
                (int)Math.ceil(Math.PI * 2.0 * rx / 2.0)));
        double cos = Math.cos(rotation), sin = Math.sin(rotation);
        double[] xs = new double[segments];
        double[] ys = new double[segments];
        for (int i = 0; i < segments; i++) {
            double angle = Math.PI * 2.0 * i / segments;
            double ex = Math.cos(angle) * rx;
            double ey = Math.sin(angle) * ry;
            xs[i] = cx + ex * cos - ey * sin;
            ys[i] = cy + ex * sin + ey * cos;
        }
        polyline(g, xs, ys, color, 1.15f, true);
    }

    public static void line(GuiGraphics g, int x0, int y0, int x1, int y1, int color) {
        line(g, x0, y0, x1, y1, color, 1);
    }

    public static void line(GuiGraphics g, int x0, int y0, int x1, int y1,
                            int color, int thickness) {
        if (thickness <= 0) return;
        polyline(g, new double[]{x0, x1}, new double[]{y0, y1},
                color, Math.max(0.8f, Math.min(4.0f, thickness)), false);
    }

    /** Smooth quadratic connection used for route and sector-link overlays. */
    public static void curvedLine(GuiGraphics g, double x0, double y0, double x1, double y1,
                                  double bend, int color, float thickness) {
        double dx = x1 - x0, dy = y1 - y0;
        double length = Math.hypot(dx, dy);
        if (length < 1.0) return;
        double nx = -dy / length, ny = dx / length;
        double cx = (x0 + x1) * 0.5 + nx * bend;
        double cy = (y0 + y1) * 0.5 + ny * bend;
        int segments = Math.max(12, Math.min(96, (int)(length / 5.0)));
        double[] xs = new double[segments + 1];
        double[] ys = new double[segments + 1];
        for (int i = 0; i <= segments; i++) {
            double t = (double)i / segments, u = 1.0 - t;
            xs[i] = u * u * x0 + 2.0 * u * t * cx + t * t * x1;
            ys[i] = u * u * y0 + 2.0 * u * t * cy + t * t * y1;
        }
        polyline(g, xs, ys, color, Math.max(0.8f, thickness), false);
    }

    /**
     * Draws a softly filled, rounded convex territory around systems sharing
     * the same sector identifier. The supplied points are already in screen space.
     */
    public static void territory(GuiGraphics g, List<double[]> points,
                                 double padding, int color) {
        if (points == null || points.size() < 3) return;
        List<double[]> hull = convexHull(points);
        if (hull.size() < 3) return;
        List<double[]> expanded = expandHull(hull, Math.max(8.0, padding));
        List<double[]> rounded = roundPolygon(expanded, 0.16, 7);
        if (rounded.size() < 3) return;

        int fillColor = (color & 0x00FFFFFF) | 0x18000000;
        fillPolygon(g, rounded, fillColor);
        double[] xs = new double[rounded.size()];
        double[] ys = new double[rounded.size()];
        for (int i = 0; i < rounded.size(); i++) {
            xs[i] = rounded.get(i)[0];
            ys[i] = rounded.get(i)[1];
        }
        polyline(g, xs, ys, color, 1.35f, true);
    }

    private static List<double[]> convexHull(List<double[]> source) {
        List<double[]> points = new ArrayList<>();
        for (double[] p : source) {
            if (p != null && p.length >= 2 && Double.isFinite(p[0]) && Double.isFinite(p[1]))
                points.add(new double[]{p[0], p[1]});
        }
        points.sort((a, b) -> {
            int x = Double.compare(a[0], b[0]);
            return x != 0 ? x : Double.compare(a[1], b[1]);
        });
        if (points.size() <= 3) return points;
        List<double[]> lower = new ArrayList<>(), upper = new ArrayList<>();
        for (double[] p : points) {
            while (lower.size() >= 2 && cross(lower.get(lower.size() - 2),
                    lower.get(lower.size() - 1), p) <= 0) lower.remove(lower.size() - 1);
            lower.add(p);
        }
        for (int i = points.size() - 1; i >= 0; i--) {
            double[] p = points.get(i);
            while (upper.size() >= 2 && cross(upper.get(upper.size() - 2),
                    upper.get(upper.size() - 1), p) <= 0) upper.remove(upper.size() - 1);
            upper.add(p);
        }
        lower.remove(lower.size() - 1);
        upper.remove(upper.size() - 1);
        lower.addAll(upper);
        return lower;
    }

    private static double cross(double[] o, double[] a, double[] b) {
        return (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0]);
    }

    private static List<double[]> expandHull(List<double[]> hull, double padding) {
        double cx = 0, cy = 0;
        for (double[] p : hull) { cx += p[0]; cy += p[1]; }
        cx /= hull.size(); cy /= hull.size();
        List<double[]> expanded = new ArrayList<>(hull.size());
        for (double[] p : hull) {
            double dx = p[0] - cx, dy = p[1] - cy;
            double length = Math.max(1.0, Math.hypot(dx, dy));
            expanded.add(new double[]{p[0] + dx / length * padding,
                    p[1] + dy / length * padding});
        }
        return expanded;
    }

    private static List<double[]> roundPolygon(List<double[]> polygon, double cut, int steps) {
        List<double[]> result = new ArrayList<>();
        for (int i = 0; i < polygon.size(); i++) {
            double[] prev = polygon.get((i + polygon.size() - 1) % polygon.size());
            double[] current = polygon.get(i);
            double[] next = polygon.get((i + 1) % polygon.size());
            double[] start = lerp(current, prev, cut);
            double[] end = lerp(current, next, cut);
            result.add(start);
            for (int s = 1; s <= steps; s++) {
                double t = (double)s / steps, u = 1.0 - t;
                result.add(new double[]{
                        u * u * start[0] + 2 * u * t * current[0] + t * t * end[0],
                        u * u * start[1] + 2 * u * t * current[1] + t * t * end[1]
                });
            }
        }
        return result;
    }

    private static double[] lerp(double[] a, double[] b, double t) {
        return new double[]{a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t};
    }

    private static void fillPolygon(GuiGraphics g, List<double[]> points, int color) {
        if (points.size() < 3) return;
        BufferBuilder builder = beginMesh();
        double[] center = new double[2];
        for (double[] p : points) { center[0] += p[0]; center[1] += p[1]; }
        center[0] /= points.size(); center[1] /= points.size();
        for (int i = 0; i < points.size(); i++) {
            double[] a = points.get(i), b = points.get((i + 1) % points.size());
            vertex(builder, center[0], center[1], color);
            vertex(builder, a[0], a[1], color);
            vertex(builder, b[0], b[1], color);
        }
        drawMesh(g, builder);
    }

    private static void polyline(GuiGraphics g, double[] xs, double[] ys,
                                 int color, float thickness, boolean closed) {
        if (xs == null || ys == null || xs.length != ys.length || xs.length < 2) return;
        BufferBuilder builder = beginMesh();
        float half = Math.max(0.35f, thickness * 0.5f);
        int segments = closed ? xs.length : xs.length - 1;
        for (int i = 0; i < segments; i++) {
            int j = (i + 1) % xs.length;
            double dx = xs[j] - xs[i], dy = ys[j] - ys[i];
            double length = Math.hypot(dx, dy);
            if (length < 1.0E-4) continue;
            double nx = -dy / length, ny = dx / length;
            double ax = xs[i], ay = ys[i], bx = xs[j], by = ys[j];
            // Opaque stroke core.
            quad(builder, ax + nx * half, ay + ny * half,
                    ax - nx * half, ay - ny * half,
                    bx - nx * half, by - ny * half,
                    bx + nx * half, by + ny * half, color, color, color, color);
            // Transparent fringe on both sides gives the edge sub-pixel coverage.
            int transparent = color & 0x00FFFFFF;
            quad(builder, ax + nx * half, ay + ny * half,
                    ax + nx * (half + EDGE_SOFTNESS), ay + ny * (half + EDGE_SOFTNESS),
                    bx + nx * (half + EDGE_SOFTNESS), by + ny * (half + EDGE_SOFTNESS),
                    bx + nx * half, by + ny * half, color, transparent, transparent, color);
            quad(builder, ax - nx * half, ay - ny * half,
                    ax - nx * (half + EDGE_SOFTNESS), ay - ny * (half + EDGE_SOFTNESS),
                    bx - nx * (half + EDGE_SOFTNESS), by - ny * (half + EDGE_SOFTNESS),
                    bx - nx * half, by - ny * half, color, transparent, transparent, color);
        }
        // Open routes get round end caps. Closed orbital paths use overlapping
        // segment cores, avoiding thousands of redundant cap triangles per orbit.
        if (!closed) {
            disc(builder, xs[0], ys[0], half, color);
            disc(builder, xs[xs.length - 1], ys[ys.length - 1], half, color);
        }
        drawMesh(g, builder);
    }

    private static void disc(BufferBuilder builder, double cx, double cy, double radius, int color) {
        int steps = 10;
        int transparent = color & 0x00FFFFFF;
        for (int i = 0; i < steps; i++) {
            double a0 = Math.PI * 2.0 * i / steps;
            double a1 = Math.PI * 2.0 * (i + 1) / steps;
            double x0 = cx + Math.cos(a0) * radius, y0 = cy + Math.sin(a0) * radius;
            double x1 = cx + Math.cos(a1) * radius, y1 = cy + Math.sin(a1) * radius;
            vertex(builder, cx, cy, color);
            vertex(builder, x0, y0, color);
            vertex(builder, x1, y1, color);
            double ox0 = cx + Math.cos(a0) * (radius + EDGE_SOFTNESS);
            double oy0 = cy + Math.sin(a0) * (radius + EDGE_SOFTNESS);
            double ox1 = cx + Math.cos(a1) * (radius + EDGE_SOFTNESS);
            double oy1 = cy + Math.sin(a1) * (radius + EDGE_SOFTNESS);
            quad(builder, x0, y0, ox0, oy0, ox1, oy1, x1, y1,
                    color, transparent, transparent, color);
        }
    }

    private static void quad(BufferBuilder builder,
                             double x0, double y0, double x1, double y1,
                             double x2, double y2, double x3, double y3,
                             int c0, int c1, int c2, int c3) {
        vertex(builder, x0, y0, c0); vertex(builder, x1, y1, c1); vertex(builder, x2, y2, c2);
        vertex(builder, x0, y0, c0); vertex(builder, x2, y2, c2); vertex(builder, x3, y3, c3);
    }

    private static BufferBuilder beginMesh() {
        BufferBuilder builder = Tesselator.getInstance().getBuilder();
        builder.begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);
        return builder;
    }

    private static void vertex(BufferBuilder builder, double x, double y, int argb) {
        builder.vertex((float)x, (float)y, 0.0f)
                .color((argb >> 16) & 255, (argb >> 8) & 255, argb & 255,
                        (argb >>> 24) & 255).endVertex();
    }

    private static void drawMesh(GuiGraphics graphics, BufferBuilder builder) {
        graphics.flush();
        PoseStack modelView = RenderSystem.getModelViewStack();
        modelView.pushPose();
        modelView.mulPoseMatrix(graphics.pose().last().pose());
        RenderSystem.applyModelViewMatrix();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableCull();
        RenderSystem.setShaderColor(1, 1, 1, 1);
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        try {
            BufferUploader.drawWithShader(builder.end());
        } finally {
            RenderSystem.setShaderColor(1, 1, 1, 1);
            RenderSystem.enableCull();
            RenderSystem.disableBlend();
            modelView.popPose();
            RenderSystem.applyModelViewMatrix();
        }
    }
}
