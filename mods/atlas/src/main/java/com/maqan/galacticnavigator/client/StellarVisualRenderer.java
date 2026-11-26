package com.maqan.galacticnavigator.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

/**
 * Dedicated animated stellar renderer for navigator maps.
 *
 * This intentionally renders stars as luminous stellar bodies while leaving
 * Cosmic Horizons cube geometry untouched for planets and moons.
 */
public final class StellarVisualRenderer {
    private StellarVisualRenderer() {}

    public static void render(GuiGraphics graphics, int centerX, int centerY,
                              int sourceRadius, int bloom, int core) {
        int radius = Math.max(4, Math.min(180, sourceRadius));
        Minecraft minecraft = Minecraft.getInstance();
        double ticks = minecraft.level == null ? System.nanoTime() / 50_000_000.0
                : minecraft.level.getGameTime() + minecraft.getFrameTime();
        double phase = ticks * 0.035;
        double pulse = 1.0 + 0.045 * Math.sin(phase);
        int corona = Math.max(radius + 5, (int)Math.round(radius * (2.7 + 0.12 * Math.sin(phase * 0.63))));
        int outerAlpha = Math.max(12, Math.min(76, (bloom >>> 24) / 3));
        int glowRgb = bloom & 0x00FFFFFF;

        // Broad corona: nested horizontal slices form a soft radial envelope.
        int rows = Math.max(24, Math.min(96, corona * 2));
        for (int row = 0; row < rows; row++) {
            double ny = ((row + 0.5) / rows) * 2.0 - 1.0;
            double profile = Math.sqrt(Math.max(0.0, 1.0 - ny * ny));
            int half = (int)(corona * profile);
            double falloff = Math.pow(Math.max(0.0, 1.0 - Math.abs(ny)), 1.6);
            int alpha = Math.max(1, (int)(outerAlpha * falloff));
            int y = centerY - corona + (int)(2.0 * corona * row / rows);
            int h = Math.max(1, (2 * corona) / rows);
            graphics.fill(centerX - half, y, centerX + half + 1, y + h,
                    (alpha << 24) | glowRgb);
        }

        // Animated stellar rays. They remain inside the caller's panel scissor.
        int rayLength = Math.max(3, (int)(radius * (1.45 + 0.12 * Math.sin(phase * 0.8))));
        int rayColor = (Math.min(170, outerAlpha * 2) << 24) | glowRgb;
        for (int ray = 0; ray < 12; ray++) {
            double angle = phase * 0.08 + Math.PI * 2.0 * ray / 12.0;
            int inner = radius + 2;
            int outer = radius + rayLength + (ray % 3 == 0 ? radius / 3 : 0);
            int x0 = centerX + (int)(Math.cos(angle) * inner);
            int y0 = centerY + (int)(Math.sin(angle) * inner);
            int x1 = centerX + (int)(Math.cos(angle) * outer);
            int y1 = centerY + (int)(Math.sin(angle) * outer);
            SolarSystemRenderer.line(graphics, x0, y0, x1, y1, rayColor, ray % 3 == 0 ? 2 : 1);
        }

        // Bright photosphere with a warm inner core and a subtle animated rim.
        int photosphere = Math.max(4, (int)Math.round(radius * pulse));
        int rim = Math.max(photosphere + 2, photosphere + Math.max(2, photosphere / 5));
        int rimAlpha = Math.min(190, Math.max(45, (core >>> 24) / 2));
        int rimColor = (rimAlpha << 24) | glowRgb;
        for (int y = -rim; y <= rim; y++) {
            int halfOuter = (int)Math.sqrt(Math.max(0, rim * rim - y * y));
            int halfInner = (int)Math.sqrt(Math.max(0, photosphere * photosphere - y * y));
            if (halfOuter > halfInner) {
                graphics.fill(centerX - halfOuter, centerY + y, centerX - halfInner, centerY + y + 1, rimColor);
                graphics.fill(centerX + halfInner + 1, centerY + y, centerX + halfOuter + 1, centerY + y + 1, rimColor);
            }
        }
        int warm = mix(core, 0xFFFFF4D8, 0.42);
        int hot = mix(core, 0xFFFFFFFF, 0.72);
        for (int y = -photosphere; y <= photosphere; y++) {
            int half = (int)Math.sqrt(Math.max(0, photosphere * photosphere - y * y));
            double vertical = (double)(y + photosphere) / Math.max(1, photosphere * 2);
            int color = vertical < 0.38 ? hot : vertical < 0.72 ? warm : core;
            graphics.fill(centerX - half, centerY + y, centerX + half + 1, centerY + y + 1, color);
        }
        graphics.fill(centerX - Math.max(1, photosphere / 4), centerY - Math.max(1, photosphere / 3),
                centerX + 1, centerY - Math.max(0, photosphere / 3) + 1, 0x99FFFFFF);
    }

    private static int mix(int a, int b, double amount) {
        double t = Math.max(0.0, Math.min(1.0, amount));
        int aa = (a >>> 24) & 255, ar = (a >>> 16) & 255, ag = (a >>> 8) & 255, ab = a & 255;
        int ba = (b >>> 24) & 255, br = (b >>> 16) & 255, bg = (b >>> 8) & 255, bb = b & 255;
        return ((int)(aa + (ba - aa) * t) << 24)
                | ((int)(ar + (br - ar) * t) << 16)
                | ((int)(ag + (bg - ag) * t) << 8)
                | (int)(ab + (bb - ab) * t);
    }
}
