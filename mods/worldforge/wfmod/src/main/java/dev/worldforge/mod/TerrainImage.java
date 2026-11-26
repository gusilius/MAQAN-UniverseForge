package dev.worldforge.mod;

/** Основа карты: цвета биомов, тени рельефа, реки. Результат: ARGB w*h. Без зависимостей от Minecraft. */
public final class TerrainImage {
    private TerrainImage() {}

    public static int[] render(MapData m) {
        int w = m.w, h = m.h;
        int[] out = new int[w * h];
        double span = m.maxY - m.minY;
        double lx = -0.55, lz = -0.55, ly = 0.63;               // свет с северо-запада
        for (int z = 0; z < h; z++) {
            for (int x = 0; x < w; x++) {
                int base = m.biomeRgb[m.biomeAtPx(x, z)];
                double e = m.minY + m.rawHeightPx(x, z) / 65535.0 * span;
                double dx = (m.rawHeightPx(x + 1, z) - m.rawHeightPx(x - 1, z)) / 65535.0 * span / (2 * m.bpp) * 3.0;
                double dz = (m.rawHeightPx(x, z + 1) - m.rawHeightPx(x, z - 1)) / 65535.0 * span / (2 * m.bpp) * 3.0;
                double nl = Math.sqrt(dx * dx + dz * dz + 1);
                double shade = (-dx * lx - dz * lz + ly) / nl;   // скалярное произведение нормали и света
                double f = e < m.seaLevel ? 1.0 - Math.min(0.35, (m.seaLevel - e) / 220.0) : 0.78 + 0.5 * (shade - 0.63);
                f = Math.max(0.45, Math.min(1.25, f));
                int r = (base >> 16) & 255, g = (base >> 8) & 255, b = base & 255;
                int rv = m.riverAtPx(x, z);
                if (rv != 0 && e >= m.seaLevel - 1) {
                    double a = rv == 255 ? 0.85 : 0.9;
                    r = (int) (r * (1 - a) + 52 * a);
                    g = (int) (g * (1 - a) + 104 * a);
                    b = (int) (b * (1 - a) + 186 * a);
                    f = Math.max(f, 0.95);
                }
                out[z * w + x] = 0xFF000000 | (clamp(r * f) << 16) | (clamp(g * f) << 8) | clamp(b * f);
            }
        }
        return out;
    }

    private static int clamp(double v) {
        return v < 0 ? 0 : (v > 255 ? 255 : (int) v);
    }
}
