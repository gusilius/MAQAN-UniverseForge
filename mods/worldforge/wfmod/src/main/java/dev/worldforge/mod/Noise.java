package dev.worldforge.mod;

/** Быстрый градиентный шум (Perlin) 2D/3D. Детерминирован по seed, без зависимостей от Minecraft. */
public final class Noise {
    private final long seed;

    public Noise(long seed) {
        this.seed = seed;
    }

    private int h(int x, int y, int z) {
        long v = x * 374761393L ^ y * 668265263L ^ z * 2147483647L ^ seed * 1442695041L;
        v = (v ^ (v >>> 13)) * 1274126177L;
        v ^= v >>> 16;
        return (int) (v ^ (v >>> 32));
    }

    private static double fade(double t) {
        return t * t * t * (t * (t * 6 - 15) + 10);
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    private static double g2(int hash, double x, double z) {
        switch (hash & 7) {
            case 0: return x + z;
            case 1: return x - z;
            case 2: return -x + z;
            case 3: return -x - z;
            case 4: return x;
            case 5: return -x;
            case 6: return z;
            default: return -z;
        }
    }

    private static double g3(int hash, double x, double y, double z) {
        int k = hash & 15;
        double u = k < 8 ? x : y;
        double v = k < 4 ? y : (k == 12 || k == 14 ? x : z);
        return ((k & 1) == 0 ? u : -u) + ((k & 2) == 0 ? v : -v);
    }

    /** Значения примерно от -1 до 1. */
    public double noise2(double x, double z) {
        int xi = (int) Math.floor(x), zi = (int) Math.floor(z);
        double fx = x - xi, fz = z - zi, u = fade(fx), v = fade(fz);
        double a = g2(h(xi, 0, zi), fx, fz);
        double b = g2(h(xi + 1, 0, zi), fx - 1, fz);
        double c = g2(h(xi, 0, zi + 1), fx, fz - 1);
        double d = g2(h(xi + 1, 0, zi + 1), fx - 1, fz - 1);
        return lerp(lerp(a, b, u), lerp(c, d, u), v);
    }

    /** Значения примерно от -1 до 1. */
    public double noise3(double x, double y, double z) {
        int xi = (int) Math.floor(x), yi = (int) Math.floor(y), zi = (int) Math.floor(z);
        double fx = x - xi, fy = y - yi, fz = z - zi, u = fade(fx), v = fade(fy), w = fade(fz);
        double n000 = g3(h(xi, yi, zi), fx, fy, fz);
        double n100 = g3(h(xi + 1, yi, zi), fx - 1, fy, fz);
        double n010 = g3(h(xi, yi + 1, zi), fx, fy - 1, fz);
        double n110 = g3(h(xi + 1, yi + 1, zi), fx - 1, fy - 1, fz);
        double n001 = g3(h(xi, yi, zi + 1), fx, fy, fz - 1);
        double n101 = g3(h(xi + 1, yi, zi + 1), fx - 1, fy, fz - 1);
        double n011 = g3(h(xi, yi + 1, zi + 1), fx, fy - 1, fz - 1);
        double n111 = g3(h(xi + 1, yi + 1, zi + 1), fx - 1, fy - 1, fz - 1);
        return lerp(lerp(lerp(n000, n100, u), lerp(n010, n110, u), v),
                lerp(lerp(n001, n101, u), lerp(n011, n111, u), v), w);
    }

    public double fbm2(double x, double z, int octaves) {
        double sum = 0, amp = 1, tot = 0, f = 1;
        for (int o = 0; o < octaves; o++) {
            sum += amp * noise2(x * f, z * f);
            tot += amp;
            amp *= 0.5;
            f *= 2;
        }
        return sum / tot;
    }
}
