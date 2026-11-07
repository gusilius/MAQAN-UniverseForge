package dev.worldforge.genesis;

import java.awt.Color;
import java.util.Locale;

/** Цвета внешности: "#rrggbb"; сдвиг оттенка, насыщенности и яркости для тонкой настройки. */
public final class Colors {
    private Colors() {}

    public static boolean valid(String s) { return s != null && s.matches("#[0-9a-fA-F]{6}"); }

    public static String hex(int rgb) { return String.format(Locale.ROOT, "#%06x", rgb & 0xFFFFFF); }

    public static int rgb(String s) { return valid(s) ? Integer.parseInt(s.substring(1), 16) : 0x808080; }

    /** Сдвиг: dh - градусы оттенка (-180..180), ds и db - проценты (-100..100). */
    public static String shift(String hex, int dh, int ds, int db) {
        int c = rgb(hex);
        float[] hsb = Color.RGBtoHSB((c >> 16) & 255, (c >> 8) & 255, c & 255, null);
        float h = (hsb[0] + dh / 360f) % 1f;
        if (h < 0) h += 1f;
        float s = Math.max(0f, Math.min(1f, hsb[1] + ds / 100f));
        float b = Math.max(0.05f, Math.min(1f, hsb[2] + db / 100f));
        return hex(Color.HSBtoRGB(h, s, b));
    }
}
