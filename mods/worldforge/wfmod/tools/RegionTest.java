import dev.worldforge.mod.*;
import java.io.*;
import java.util.*;
import java.util.zip.*;

/** Проверка регионов на реальном датапаке: столицы лежат в своих регионах, доли площади совпадают с regions.json. */
public class RegionTest {
    public static void main(String[] a) throws Exception {
        String id = a[1], ns = id.substring(0, id.indexOf(':')), name = id.substring(id.indexOf(':') + 1);
        try (ZipFile zf = new ZipFile(a[0])) {
            MapData m = MapData.load(id, file -> {
                ZipEntry e = zf.getEntry("data/" + ns + "/worldforge_maps/" + name + "/" + file);
                if (e == null) return null;
                try (InputStream in = zf.getInputStream(e)) { return in.readAllBytes(); }
            });
            RegionData rd = m.regions;
            System.out.println("регионов: " + rd.list.size() + ", карта-предмет: " + (m.regionMap == null ? "нет" : m.regionMap.length + " байт"));
            int bad = 0;
            for (RegionData.Region r : rd.list) {
                int got = m.regionAt(r.capX + 0.5, r.capZ + 0.5);
                if (got != r.id) { bad++; System.out.println("  столица " + r.name + " попала в регион " + got); }
            }
            System.out.println("столицы в своих регионах: " + (rd.list.size() - bad) + "/" + rd.list.size());
            Random rnd = new Random(1);
            int half = (int) (m.w * m.bpp / 2);
            long[] cnt = new long[rd.list.size() + 2];
            long inRegion = 0;
            for (int i = 0; i < 600000; i++) {
                int id2 = m.regionAt(rnd.nextInt(2 * half) - half, rnd.nextInt(2 * half) - half);
                if (id2 > 0) { cnt[id2]++; inRegion++; }
            }
            double maxErr = 0;
            for (RegionData.Region r : rd.list) {
                double pct = 100.0 * cnt[r.id] / inRegion, err = Math.abs(pct - r.areaPct);
                maxErr = Math.max(maxErr, err);
            }
            System.out.printf("макс. расхождение долей площади (Java против regions.json): %.2f п.п.%n", maxErr);
            RegionData.Region f = rd.find("сев");
            System.out.println("поиск 'сев' -> " + (f == null ? "нет" : f.name));
        }
    }
}
