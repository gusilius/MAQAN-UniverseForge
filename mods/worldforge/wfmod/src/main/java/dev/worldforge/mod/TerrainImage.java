package dev.worldforge.mod;

/**
 * Географическая основа атласа: биомы, высотная тень, береговые линии, горизонтали,
 * русла рек и условные знаки лесов/гор. Все символы привязаны к исходным слоям карты,
 * поэтому одинаковы на клиенте, в экспорте и в повторных рендерах.
 */
public final class TerrainImage {
    private static final int COAST_INK = 0xFF5B4935;
    private static final int COAST_LIGHT = 0xFFD8C59A;
    private static final int CONTOUR_INK = 0xFF8B7353;
    private static final int CONTOUR_MAJOR = 0xFF765B3C;
    private static final int RIVER_BANK = 0xFF365F78;
    private static final int RIVER_INK = 0xFF4E91B5;
    private static final int FOREST_DARK = 0xFF31563B;
    private static final int FOREST_LIGHT = 0xFF71915A;
    private static final int TREE_TRUNK = 0xFF60452F;
    private static final int HACHURE_INK = 0xFF625B55;

    private TerrainImage() {}

    public static int[] render(MapData m) {
        if (m == null || m.w <= 0 || m.h <= 0 || (long) m.w * m.h > 16L * 1024L * 1024L)
            throw new IllegalArgumentException("Некорректные размеры географической карты");
        int w = m.w, h = m.h;
        int[] out = new int[Math.multiplyExact(w, h)];
        double span = m.maxY - m.minY;
        double lx = -0.55, lz = -0.55, ly = 0.63; // свет с северо-запада
        for (int z = 0; z < h; z++) {
            for (int x = 0; x < w; x++) {
                int base = m.biomeRgb[m.biomeAtPx(x, z)];
                double elevation = elevation(m, x, z);
                double dx = (m.rawHeightPx(x + 1, z) - m.rawHeightPx(x - 1, z)) / 65535.0 * span / (2 * m.bpp) * 3.0;
                double dz = (m.rawHeightPx(x, z + 1) - m.rawHeightPx(x, z - 1)) / 65535.0 * span / (2 * m.bpp) * 3.0;
                double normalLength = Math.sqrt(dx * dx + dz * dz + 1);
                double shade = (-dx * lx - dz * lz + ly) / normalLength;
                double factor = elevation < m.seaLevel
                        ? 1.0 - Math.min(0.35, (m.seaLevel - elevation) / 220.0)
                        : 0.78 + 0.5 * (shade - 0.63);
                factor = Math.max(0.45, Math.min(1.25, factor));
                int r = (base >> 16) & 255, g = (base >> 8) & 255, b = base & 255;
                int river = m.riverAtPx(x, z);
                if (river != 0 && elevation >= m.seaLevel - 1) {
                    double alpha = river == 255 ? 0.85 : 0.9;
                    r = (int) (r * (1 - alpha) + 52 * alpha);
                    g = (int) (g * (1 - alpha) + 104 * alpha);
                    b = (int) (b * (1 - alpha) + 186 * alpha);
                    factor = Math.max(factor, 0.95);
                }
                out[z * w + x] = 0xFF000000 | (clamp(r * factor) << 16)
                        | (clamp(g * factor) << 8) | clamp(b * factor);
            }
        }

        // Географические линии строятся из реальной высотной сетки, а не из яркости biome-растра.
        drawCoastlines(m, out);
        drawContours(m, out);
        drawRiverBanks(m, out);
        drawMountainHachures(m, out);
        drawForestSymbols(m, out);
        return out;
    }

    private static double elevation(MapData m, int x, int z) {
        return m.minY + m.rawHeightPx(x, z) / 65535.0 * (m.maxY - m.minY);
    }

    private static void drawCoastlines(MapData m, int[] out) {
        for (int z = 0; z < m.h; z++) for (int x = 0; x < m.w; x++) {
            double here = elevation(m, x, z);
            boolean land = here >= m.seaLevel;
            boolean coast = (x > 0 && (elevation(m, x - 1, z) >= m.seaLevel) != land)
                    || (x + 1 < m.w && (elevation(m, x + 1, z) >= m.seaLevel) != land)
                    || (z > 0 && (elevation(m, x, z - 1) >= m.seaLevel) != land)
                    || (z + 1 < m.h && (elevation(m, x, z + 1) >= m.seaLevel) != land);
            if (coast) {
                int i = z * m.w + x;
                out[i] = mix(out[i], land ? COAST_INK : COAST_LIGHT, land ? 0.46 : 0.32);
            }
        }
    }

    private static void drawContours(MapData m, int[] out) {
        final double interval = 32.0;
        for (int z = 1; z < m.h - 1; z++) for (int x = 1; x < m.w - 1; x++) {
            double y = elevation(m, x, z);
            if (y < m.seaLevel + 12) continue;
            int band = (int) Math.floor((y - m.seaLevel) / interval);
            boolean crossing = band != (int) Math.floor((elevation(m, x - 1, z) - m.seaLevel) / interval)
                    || band != (int) Math.floor((elevation(m, x, z - 1) - m.seaLevel) / interval);
            if (!crossing) continue;
            boolean major = Math.floorMod(band, 4) == 0;
            int i = z * m.w + x;
            out[i] = mix(out[i], major ? CONTOUR_MAJOR : CONTOUR_INK, major ? 0.34 : 0.16);
        }
    }

    private static void drawRiverBanks(MapData m, int[] out) {
        for (int z = 1; z < m.h - 1; z++) for (int x = 1; x < m.w - 1; x++) {
            if (m.riverAtPx(x, z) != 0) continue;
            boolean besideRiver = m.riverAtPx(x - 1, z) != 0 || m.riverAtPx(x + 1, z) != 0
                    || m.riverAtPx(x, z - 1) != 0 || m.riverAtPx(x, z + 1) != 0;
            if (besideRiver && elevation(m, x, z) >= m.seaLevel) {
                int i = z * m.w + x;
                out[i] = mix(out[i], RIVER_BANK, 0.18);
            }
        }
        // A narrow light catch gives the river a drawn, legible edge at ordinary map zoom.
        for (int z = 0; z < m.h; z++) for (int x = 0; x < m.w; x++) {
            if (m.riverAtPx(x, z) != 255) continue;
            int i = z * m.w + x;
            out[i] = mix(out[i], RIVER_INK, 0.16);
        }
    }

    private static void drawMountainHachures(MapData m, int[] out) {
        int spacing = symbolSpacing(m);
        double span = m.maxY - m.minY;
        for (int z = 3; z < m.h - 3; z += spacing) for (int x = 3; x < m.w - 3; x += spacing) {
            double y = elevation(m, x, z);
            if (y < m.seaLevel + 180) continue;
            double dx = (m.rawHeightPx(x + 1, z) - m.rawHeightPx(x - 1, z)) / 65535.0 * span / (2 * m.bpp);
            double dz = (m.rawHeightPx(x, z + 1) - m.rawHeightPx(x, z - 1)) / 65535.0 * span / (2 * m.bpp);
            double slope = Math.sqrt(dx * dx + dz * dz);
            if (slope < 0.22 || Math.floorMod(hash(x, z), 5) != 0) continue;
            int sx = Math.abs(dx) > Math.abs(dz) ? 0 : 1;
            int sy = sx == 0 ? 1 : 0;
            int px = x + Math.floorMod(hash(x, z), 3) - 1;
            int py = z + Math.floorMod(hash(z, x), 3) - 1;
            for (int k = -2; k <= 2; k++) {
                int xx = px + sx * k, yy = py + sy * k;
                if (xx >= 0 && yy >= 0 && xx < m.w && yy < m.h)
                    out[yy * m.w + xx] = mix(out[yy * m.w + xx], HACHURE_INK, 0.28);
            }
        }
    }

    private static void drawForestSymbols(MapData m, int[] out) {
        int spacing = symbolSpacing(m);
        for (int cellZ = 0, z = spacing / 2; z < m.h - 3; cellZ++, z += spacing) {
            for (int cellX = 0, x = spacing / 2; x < m.w - 3; cellX++, x += spacing) {
                int jitterX = Math.floorMod(hash(cellX, cellZ), Math.max(1, spacing / 4));
                int jitterZ = Math.floorMod(hash(cellZ, cellX), Math.max(1, spacing / 4));
                int cx = Math.min(m.w - 3, x + jitterX), cz = Math.min(m.h - 3, z + jitterZ);
                if (!isForestBiome(m, m.biomeAtPx(cx, cz)) || elevation(m, cx, cz) < m.seaLevel + 3
                        || m.riverAtPx(cx, cz) != 0) continue;
                drawTree(out, m.w, m.h, cx, cz);
            }
        }
    }

    private static boolean isForestBiome(MapData m, int index) {
        if (index < 0 || index >= m.keys.length || m.keys[index] == null) return false;
        String key = m.keys[index].toLowerCase(java.util.Locale.ROOT);
        return key.contains("forest") || key.contains("wood") || key.contains("jungle")
                || key.contains("taiga") || key.contains("grove") || key.contains("mangrove");
    }

    private static void drawTree(int[] out, int w, int h, int x, int z) {
        plot(out, w, h, x, z - 2, FOREST_LIGHT, 0.62);
        plot(out, w, h, x - 1, z - 1, FOREST_DARK, 0.72);
        plot(out, w, h, x, z - 1, FOREST_LIGHT, 0.76);
        plot(out, w, h, x + 1, z - 1, FOREST_DARK, 0.72);
        plot(out, w, h, x - 2, z, FOREST_DARK, 0.58);
        plot(out, w, h, x - 1, z, FOREST_LIGHT, 0.76);
        plot(out, w, h, x, z, FOREST_DARK, 0.78);
        plot(out, w, h, x + 1, z, FOREST_LIGHT, 0.76);
        plot(out, w, h, x + 2, z, FOREST_DARK, 0.58);
        plot(out, w, h, x - 1, z + 1, FOREST_DARK, 0.62);
        plot(out, w, h, x, z + 1, TREE_TRUNK, 0.78);
        plot(out, w, h, x + 1, z + 1, FOREST_DARK, 0.62);
        plot(out, w, h, x, z + 2, TREE_TRUNK, 0.78);
    }

    private static void plot(int[] out, int w, int h, int x, int y, int color, double alpha) {
        if (x >= 0 && y >= 0 && x < w && y < h) {
            int i = y * w + x;
            out[i] = mix(out[i], color, alpha);
        }
    }

    private static int symbolSpacing(MapData m) {
        double scale = Math.sqrt(Math.max(0.25, m.bpp / 16.0));
        return Math.max(12, Math.min(36, (int) Math.round(20 * scale)));
    }

    private static int hash(int x, int y) {
        int h = x * 0x45d9f3b ^ Integer.rotateLeft(y * 0x119de1f3, 13);
        h ^= h >>> 16;
        h *= 0x7feb352d;
        h ^= h >>> 15;
        return h & 0x7fffffff;
    }

    private static int mix(int a, int b, double alpha) {
        double k = Math.max(0, Math.min(1, alpha));
        int r = (int) (((a >> 16) & 255) * (1 - k) + ((b >> 16) & 255) * k);
        int g = (int) (((a >> 8) & 255) * (1 - k) + ((b >> 8) & 255) * k);
        int bl = (int) ((a & 255) * (1 - k) + (b & 255) * k);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    private static int clamp(double v) {
        return v < 0 ? 0 : (v > 255 ? 255 : (int) v);
    }
}
