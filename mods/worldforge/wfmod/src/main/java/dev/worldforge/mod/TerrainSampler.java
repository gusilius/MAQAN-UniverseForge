package dev.worldforge.mod;

/**
 * Считает колонку рельефа в точке мира: верх суши, уровень воды, биом, реку.
 * Чистая Java без Minecraft: ту же логику используют генератор чанков и тесты.
 */
public final class TerrainSampler {
    public static final class Col {
        public int top;      // Y верхнего твёрдого блока
        public int water;    // Y верхнего блока воды (>= top; если воды нет, равен top)
        public int biome;    // индекс биома карты
        public boolean river;
    }

    private final MapData m;
    private final Noise detailNoise, caveA, caveB;
    private final float detail;

    public TerrainSampler(MapData m, float detail) {
        this.m = m;
        this.detail = detail;
        long s = m.name.hashCode() * 0x9E3779B97F4A7C15L;
        this.detailNoise = new Noise(s ^ 0x1111L);
        this.caveA = new Noise(s ^ 0x2222L);
        this.caveB = new Noise(s ^ 0x3333L);
    }

    public MapData map() {
        return m;
    }

    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    public Col sample(int wx, int wz) {
        Col c = new Col();
        double bx = wx + 0.5, bz = wz + 0.5;
        double base = m.yAt(bx, bz);
        double rel = base - m.seaLevel;
        double amp = 1.2 + clamp(rel / 160.0, 0, 1) * 9.0;       // в горах больше мелких деталей
        double fade = clamp((rel + 6) / 14.0, 0, 1);              // у воды рельеф гладкий
        double det = (detailNoise.fbm2(wx * 0.018, wz * 0.018, 4) * amp + detailNoise.noise2(wx * 0.11, wz * 0.11) * 0.8) * detail * fade;
        int top = (int) Math.floor(base + det);
        int water = m.seaLevel;
        if (m.riverAt(bx, bz) != 0 && top > m.seaLevel + 1) {
            int lvl = m.riverY(bx, bz);
            int wl = lvl >= 0 ? Math.min(lvl, top - 1) : top - 1;
            if (wl > m.seaLevel) {
                top = wl - 3;
                water = wl;
                c.river = true;
            }
        }
        c.top = top;
        c.water = Math.max(water, top);
        // биом берём в центре четверти-блока: так же, как это делает источник биомов
        c.biome = m.biomeIndexAt(((wx >> 2) << 2) + 2, ((wz >> 2) << 2) + 2);
        return c;
    }

    /** Пещеры: пересечение двух «нулевых» поверхностей шума даёт извилистые туннели. */
    public boolean cave(int x, int y, int z) {
        double a = caveA.noise3(x * 0.011, y * 0.016, z * 0.011);
        double b = caveB.noise3(x * 0.011 + 31.7, y * 0.016, z * 0.011 - 17.3);
        return Math.abs(a) < 0.06 && Math.abs(b) < 0.06;
    }

    /** {x, y, z} точки появления игрока. */
    public int[] findSpawn() {
        int[] p = m.findSpawn();
        Col c = sample(p[0], p[1]);
        return new int[]{p[0], Math.max(c.top, c.water) + 1, p[1]};
    }
}
