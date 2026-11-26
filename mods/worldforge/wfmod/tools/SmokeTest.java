import dev.worldforge.mod.*;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;

public class SmokeTest {
    public static void main(String[] a) throws Exception {
        Path maps = Paths.get(a[0]);
        long t0 = System.currentTimeMillis();
        MapData m = MapData.load(a[1], maps);
        System.out.printf("загружено %s: %dx%d, %.3f блоков/px, sea=%d, Y=%d..%d, биомов=%d, %d мс%n",
                m.name, m.w, m.h, m.bpp, m.seaLevel, m.minY, m.maxY, m.keys.length, System.currentTimeMillis() - t0);
        TerrainSampler ts = new TerrainSampler(m, 1.0f);
        int[] sp = ts.findSpawn();
        TerrainSampler.Col c = ts.sample(sp[0], sp[2]);
        System.out.printf("спавн: x=%d y=%d z=%d, биом=%s, top=%d water=%d%n", sp[0], sp[1], sp[2], m.keys[c.biome], c.top, c.water);

        // вид сверху: шаг 8 блоков, цвет биома * подсветка по высоте
        int half = (int) (m.w * m.bpp / 2), step = 8, n = 2 * half / step;
        BufferedImage img = new BufferedImage(n, n, BufferedImage.TYPE_INT_RGB);
        Map<String, Integer> hist = new TreeMap<>();
        int[] palette = {0x0d2f5c,0x1c5a8e,0x4f9bc4,0xcfe2ee,0x4a93c9,0xe3d6a4,0xeef4f8,0xc4d0c8,0x9db6a4,0x4d7a58,0xa7c46a,0xb8d27c,0x8fbd5a,0x5f9a4a,0x3f7a45,0x2d4f38,0xd2b94f,0xe9a9c0,0x5f7f5a,0x77885f,0x8f8f98,0xa09a74,0x8c7c68,0xd8dcdc,0xf4f6f7,0xcdb86a,0xb7aa6a,0xc9b45a,0xe6cf92,0xc98a56,0x6fbf7a,0x2f7a3a,0x5a9a45,0x46745a,0x6a524a,0x6a5c52};
        long rivers = 0, caves = 0, total = 0;
        double minTop = 1e9, maxTop = -1e9;
        for (int j = 0; j < n; j++) {
            for (int i = 0; i < n; i++) {
                int wx = -half + i * step, wz = -half + j * step;
                TerrainSampler.Col k = ts.sample(wx, wz);
                total++;
                if (k.river) rivers++;
                minTop = Math.min(minTop, k.top); maxTop = Math.max(maxTop, k.top);
                hist.merge(m.keys[k.biome], 1, Integer::sum);
                int col = palette[Math.min(k.biome, palette.length - 1)];
                double shade = Math.max(0.6, Math.min(1.3, 0.85 + (k.top - m.seaLevel) / 400.0));
                int r = (int) Math.min(255, ((col >> 16) & 255) * shade), g = (int) Math.min(255, ((col >> 8) & 255) * shade), b = (int) Math.min(255, (col & 255) * shade);
                if (k.water > k.top && !k.river) { r = (int)(r*0.45); g = (int)(g*0.65); b = (int)Math.min(255, b*0.9+30); }
                if (k.river) { r = 60; g = 140; b = 220; }
                img.setRGB(i, j, (r << 16) | (g << 8) | b);
            }
        }
        ImageIO.write(img, "png", Paths.get(a[2]).toFile());
        System.out.printf("колонок=%d, рек=%.2f%%, Y %.0f..%.0f%n", total, 100.0 * rivers / total, minTop, maxTop);
        hist.entrySet().stream().sorted((x, y) -> y.getValue() - x.getValue()).limit(8)
                .forEach(e -> System.out.printf("  %-18s %5.1f%%%n", e.getKey(), 100.0 * e.getValue() / (n * n)));
        // пещеры: доля воздуха на глубине
        int air = 0, cnt = 0;
        for (int i = 0; i < 20000; i++) { int x = (i * 7919) % 4000 - 2000, z = (i * 104729) % 4000 - 2000; if (ts.cave(x, 20, z)) air++; cnt++; }
        System.out.printf("пещер на Y=20: %.2f%% объёма%n", 100.0 * air / cnt);
    }
}
