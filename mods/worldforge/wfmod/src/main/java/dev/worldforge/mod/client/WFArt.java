package dev.worldforge.mod.client;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/**
 * Процедурная «графика» окон без текстур, только прямоугольники: гербовые щиты государств, портреты персонажей, плитки показателей,
 * кольцевые датчики и ленты-заголовки. Всё детерминированно от id/семени, поэтому у одного государства или человека всегда один вид.
 */
public final class WFArt {
    private WFArt() {}

    public static long hash(String s) {
        long h = 0xCBF29CE484222325L;
        for (char c : s.toCharArray()) h = (h ^ c) * 0x100000001B3L;
        h ^= h >>> 29; h *= 0xBF58476D1CE4E5B9L; h ^= h >>> 32;
        return h;
    }

    public static int rgb(int rgb) { return 0xFF000000 | (rgb & 0xFFFFFF); }

    public static int mix(int a, int b, double t) {
        int r = (int) (((a >> 16) & 255) * (1 - t) + ((b >> 16) & 255) * t), g = (int) (((a >> 8) & 255) * (1 - t) + ((b >> 8) & 255) * t), bl = (int) ((a & 255) * (1 - t) + (b & 255) * t);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    public static int shade(int c, double k) { return mix(c, k < 0 ? 0xFF000000 : 0xFFFFFFFF, Math.abs(k)); }

    /** Дополнительный цвет герба: светлее или темнее основного, чтобы рисунок читался. */
    private static int second(int base, long h) {
        double lum = (((base >> 16) & 255) * 0.3 + ((base >> 8) & 255) * 0.59 + (base & 255) * 0.11) / 255;
        int alt = ((h >> 8) & 1) == 0 ? 0xFFF0E6C8 : 0xFF14141A;
        return lum > 0.62 ? 0xFF1C1C24 : lum < 0.28 ? 0xFFF0E6C8 : alt;
    }

    // ------------------------------------------------------------------ герб

    /** Щит w x h (рекомендуется h = 1.2 w): верх прямой, низ сужается к острию; поле цвета государства и фигура по семени. */
    public static void shield(GuiGraphics g, int x, int y, int w, int h, int baseRgb, String seed) {
        long hs = hash(seed);
        int base = rgb(baseRgb), sec = second(base, hs), edge = shade(base, -0.65);
        int pattern = (int) Math.floorMod(hs >>> 3, 7L);
        int straight = h * 55 / 100;
        for (int j = 0; j < h; j++) {
            double half;
            if (j < straight) half = w / 2.0;
            else { double t = (j - straight) / (double) (h - straight); half = w / 2.0 * Math.sqrt(Math.max(0, 1 - t * t * 0.96)); }
            int x0 = (int) Math.round(x + w / 2.0 - half), x1 = (int) Math.round(x + w / 2.0 + half);
            if (x1 <= x0) continue;
            for (int i = x0; i < x1; i++) {
                double u = (i - x) / (double) w, v = j / (double) h;     // 0..1 внутри щита
                boolean fig = switch (pattern) {
                    case 0 -> false;                                      // чистое поле
                    case 1 -> Math.abs(u - 0.5) < 0.09 || Math.abs(v - 0.38) < 0.07;          // крест
                    case 2 -> Math.abs((u - 0.5) - (v - 0.5)) < 0.1;                              // перевязь
                    case 3 -> Math.abs(u - 0.5) < 0.13;                                           // столб
                    case 4 -> Math.abs(v - 0.5 - Math.abs(u - 0.5) * 0.9 + 0.1) < 0.08 && v < 0.85;  // шеврон
                    case 5 -> v < 0.3;                                                            // глава
                    default -> Math.abs((u - 0.5) - (v - 0.5)) < 0.07 || Math.abs((u - 0.5) + (v - 0.5)) < 0.07;   // косой крест
                };
                int c = fig ? sec : base;
                boolean border = i == x0 || i == x1 - 1 || j == 0 || j == h - 1 || (j >= straight && (i <= x0 + 0 || i >= x1 - 1));
                g.fill(i, y + j, i + 1, y + j + 1, border ? edge : (j < 2 ? shade(c, 0.18) : c));
            }
        }
        // блик и контур по краю
        g.fill(x + 1, y + 1, x + w - 1, y + 2, 0x30FFFFFF);
    }

    // ------------------------------------------------------------------ портрет

    private static final int[] SKIN = {0xFFF1C9A5, 0xFFE0AC84, 0xFFC68A62, 0xFF9C6642, 0xFF6F4631};
    private static final int[] HAIR = {0xFF2B1E16, 0xFF4A3020, 0xFF7A5232, 0xFFB8883E, 0xFFD9C27A, 0xFF8C8C92, 0xFFC4C4CC, 0xFF7A2F1C};

    /** Портрет-бюст: фон оттенка государства, плечи в одеянии, голова, причёска, борода у зрелых мужчин, венец у правителя. */
    public static void bust(GuiGraphics g, int x, int y, int w, int h, int realmRgb, String seed, boolean male, int age, boolean crown) {
        long hs = hash(seed);
        int bg = rgb(realmRgb), skin = SKIN[(int) Math.floorMod(hs, 5L)];
        int hair = age > 58 ? HAIR[5 + (int) Math.floorMod(hs >>> 5, 2L)] : HAIR[(int) Math.floorMod(hs >>> 4, 8L)];
        int robe = mix(bg, 0xFF202028, 0.35);
        g.fill(x, y, x + w, y + h, shade(bg, -0.55));
        g.fill(x, y + h * 55 / 100, x + w, y + h, shade(bg, -0.7));               // «стена и пол»
        // плечи
        int sy = y + h * 66 / 100;
        g.fill(x + w / 10, sy, x + w - w / 10, y + h, robe);
        g.fill(x + w / 3, sy, x + w - w / 3, sy + Math.max(2, h / 10), shade(skin, -0.25));           // вырез
        g.fill(x + w / 10, sy, x + w - w / 10, sy + 1, shade(robe, 0.25));
        // шея и голова
        int hw = w * 40 / 100, hh = h * 46 / 100, hx = x + (w - hw) / 2, hy = y + h * 14 / 100;
        g.fill(x + w * 42 / 100, hy + hh - 2, x + w * 58 / 100, sy + 2, shade(skin, -0.18));
        g.fill(hx, hy + 2, hx + hw, hy + hh - 2, skin);
        g.fill(hx + 2, hy, hx + hw - 2, hy + hh, skin);
        g.fill(hx + 2, hy + hh - 3, hx + hw - 2, hy + hh, shade(skin, -0.1));
        // причёска
        int style = (int) Math.floorMod(hs >>> 9, 3L);
        if (!male && style != 2) { g.fill(hx - 2, hy, hx + 3, hy + hh + 3, hair); g.fill(hx + hw - 3, hy, hx + hw + 2, hy + hh + 3, hair); }
        g.fill(hx, hy - 1, hx + hw, hy + Math.max(3, hh / 4), hair);
        if (style == 1) { g.fill(hx - 1, hy, hx + 2, hy + hh / 2, hair); g.fill(hx + hw - 2, hy, hx + hw + 1, hy + hh / 2, hair); }
        // лицо
        int ey = hy + hh * 42 / 100, ew = Math.max(2, hw / 6);
        g.fill(hx + hw / 5, ey, hx + hw / 5 + ew, ey + 2, 0xFF1C1612);
        g.fill(hx + hw - hw / 5 - ew, ey, hx + hw - hw / 5, ey + 2, 0xFF1C1612);
        g.fill(hx + hw / 2 - 1, ey + 3, hx + hw / 2 + 1, ey + hh * 22 / 100, shade(skin, -0.2));       // нос
        g.fill(hx + hw * 3 / 10, hy + hh * 80 / 100, hx + hw * 7 / 10, hy + hh * 80 / 100 + 1, 0xFF7A3A32);   // рот
        if (male && age >= 30) {                                                                          // борода
            boolean full = age >= 45 || ((hs >>> 12) & 1) == 1;
            g.fill(hx + 1, hy + hh * 62 / 100, hx + hw - 1, hy + hh - (full ? 0 : 5), hair);
            g.fill(hx + hw * 3 / 10, hy + hh * 80 / 100, hx + hw * 7 / 10, hy + hh * 80 / 100 + 1, 0xFF7A3A32);
        }
        if (crown) {
            int cy = hy - Math.max(3, h / 12), cx0 = hx - 1, cx1 = hx + hw + 1;
            g.fill(cx0, cy + 2, cx1, cy + Math.max(5, h / 10), 0xFFE9B930);
            for (int k = 0; k < 4; k++) { int px = cx0 + (cx1 - cx0 - 2) * k / 3; g.fill(px, cy - 1, px + 2, cy + 3, 0xFFF5D460); }
            g.fill(cx0, cy + Math.max(3, h / 10) - 1, cx1, cy + Math.max(5, h / 10), 0xFFB48A1A);
        }
        // рамка
        g.fill(x, y, x + w, y + 1, 0xFF000000); g.fill(x, y + h - 1, x + w, y + h, 0xFF000000);
        g.fill(x, y, x + 1, y + h, 0xFF000000); g.fill(x + w - 1, y, x + w, y + h, 0xFF000000);
        g.fill(x + 1, y + 1, x + w - 1, y + 2, 0x30FFFFFF);
    }

    // ------------------------------------------------------------------ плитки и датчики

    /** Плитка показателя: сверху мелкая подпись, крупное значение (масштаб 2), снизу пояснение; цветной акцент слева. */
    public static void tile(GuiGraphics g, Font f, int x, int y, int w, int h, String label, String value, String sub, int color) {
        WFUi.bevel(g, x, y, w, h, true);
        g.fill(x + 2, y + 2, x + 4, y + h - 2, color);
        g.drawString(f, label, x + 9, y + 5, WFUi.MUTED);
        var pose = g.pose();
        pose.pushPose();
        pose.translate(x + 9, y + 15, 0);
        pose.scale(2f, 2f, 1f);
        g.drawString(f, value, 0, 0, color);
        pose.popPose();
        if (sub != null && !sub.isEmpty()) g.drawString(f, f.plainSubstrByWidth(sub, w - 14), x + 9, y + h - 12, WFUi.MUTED);
    }

    /** Кольцевой датчик 0..1: дуга цвета color по кругу радиуса r, значение в центре. */
    public static void ring(GuiGraphics g, Font f, int cx, int cy, int r, double frac, int color, String center) {
        frac = Math.max(0, Math.min(1, frac));
        for (int a = 0; a < 360; a += 3) {
            double rad = Math.toRadians(a - 90);
            boolean on = a / 360.0 <= frac;
            for (int k = 0; k < 3; k++) {
                int px = (int) Math.round(cx + Math.cos(rad) * (r - k)), py = (int) Math.round(cy + Math.sin(rad) * (r - k));
                g.fill(px, py, px + 1, py + 1, on ? color : 0xFF2A2C34);
            }
        }
        if (center != null) g.drawString(f, center, cx - f.width(center) / 2, cy - 4, WFUi.TX);
    }

    /** Мини-график по значениям (нормируется по максимуму). */
    public static void spark(GuiGraphics g, int x, int y, int w, int h, double[] v, int color) {
        if (v == null || v.length < 2) return;
        double max = 1e-9, min = 0;
        for (double d : v) max = Math.max(max, d);
        g.fill(x, y + h, x + w, y + h + 1, 0xFF2A2C34);
        for (int i = 0; i < v.length; i++) {
            int bx = x + i * w / v.length, bw = Math.max(1, w / v.length - 1), bh = (int) Math.round((v[i] - min) / (max - min) * h);
            g.fill(bx, y + h - bh, bx + bw, y + h, color);
        }
    }

    /** Лента-заголовок раздела: градиентная полоса с золотой нитью и текстом. */
    public static void ribbon(GuiGraphics g, Font f, int x, int y, int w, String text, int color) {
        for (int i = 0; i < 12; i++) {
            double t = i / 11.0;
            g.fill(x, y + i, x + w, y + i + 1, mix(shade(color, -0.55), shade(color, -0.8), t));
        }
        g.fill(x, y, x + w, y + 1, shade(color, 0.1));
        g.fill(x, y + 11, x + w, y + 12, 0xFF000000);
        g.fill(x, y, x + 3, y + 12, color);
        g.drawString(f, text, x + 8, y + 2, WFUi.GOLD);
    }

    /** Две стороны лицом к лицу: щиты по краям, имена под ними, «против» посередине. */
    public static void versus(GuiGraphics g, Font f, int x, int y, int w, String nameA, int rgbA, String idA, String nameB, int rgbB, String idB) {
        int sw = 30, sh = 36;
        shield(g, x + 6, y, sw, sh, rgbA, idA);
        shield(g, x + w - 6 - sw, y, sw, sh, rgbB, idB);
        String vs = "ПРОТИВ";
        g.drawString(f, vs, x + (w - f.width(vs)) / 2, y + 12, WFUi.GOLD_DIM);
        g.drawString(f, f.plainSubstrByWidth(nameA, w / 2 - 50), x + 6 + sw + 8, y + 4, WFUi.TX);
        String b = f.plainSubstrByWidth(nameB, w / 2 - 50);
        g.drawString(f, b, x + w - 6 - sw - 8 - f.width(b), y + 4, WFUi.TX);
    }
}
