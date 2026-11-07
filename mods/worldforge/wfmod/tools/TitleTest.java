import dev.worldforge.mod.*;
import java.io.*;
import java.util.*;
import java.util.zip.*;

/** Проверка иерархии и государств на реальном датапаке. */
public class TitleTest {
    public static void main(String[] a) throws Exception {
        String id = a[1], ns = id.substring(0, id.indexOf(':')), name = id.substring(id.indexOf(':') + 1);
        try (ZipFile zf = new ZipFile(a[0])) {
            MapData m = MapData.load(id, file -> {
                ZipEntry e = zf.getEntry("data/" + ns + "/worldforge_maps/" + name + "/" + file);
                if (e == null) return null;
                try (InputStream in = zf.getInputStream(e)) { return in.readAllBytes(); }
            });
            TitleData td = m.titles;
            System.out.println("уровней: " + td.tiers.size() + ", государств: " + td.realmList().size() + ", карт-предметов: " + m.mapItems.keySet());
            // 1. связность иерархии
            int bad = 0, total = 0;
            for (int i = 1; i < 100000; i++) {
                TitleData.Title t = td.get(i);
                if (t == null) continue;
                total++;
                if (t.parent != 0) {
                    TitleData.Title p = td.get(t.parent);
                    boolean listed = false;
                    for (int c : p.children) if (c == t.id) listed = true;
                    if (p.tier <= t.tier || !listed) bad++;
                }
            }
            System.out.println("титулов: " + total + ", нарушений связности: " + bad);
            // 2. столицы лежат внутри своих титулов
            int capBad = 0;
            for (int i = 1; i < 100000; i++) {
                TitleData.Title t = td.get(i);
                if (t == null) continue;
                TitleData.Title prov = m.provinceAt(t.capX + 0.5, t.capZ + 0.5);
                boolean inside = false;
                if (prov != null) for (TitleData.Title c : td.chain(prov)) if (c.id == t.id) inside = true;
                if (!inside) capBad++;
            }
            System.out.println("столиц вне своего титула: " + capBad + " из " + total);
            // 3. доли площади государств: выборка против realms.json
            Random rnd = new Random(7);
            int half = (int) (m.w * m.bpp / 2);
            Map<String, Long> cnt = new HashMap<>();
            long onLand = 0;
            for (int i = 0; i < 700000; i++) {
                TitleData.Title p = m.provinceAt(rnd.nextInt(2 * half) - half, rnd.nextInt(2 * half) - half);
                if (p == null) continue;
                onLand++;
                TitleData.Realm r = td.realmOf(p);
                if (r != null) cnt.merge(r.id, 1L, Long::sum);
            }
            double maxErr = 0, totalArea = 0;
            for (TitleData.Realm r : td.realmList()) totalArea += r.areaBlocks;
            for (TitleData.Realm r : td.realmList()) {
                double java = 100.0 * cnt.getOrDefault(r.id, 0L) / onLand, py = 100.0 * r.areaBlocks / totalArea;
                maxErr = Math.max(maxErr, Math.abs(java - py));
            }
            System.out.printf("макс. расхождение долей площади государств (Java против realms.json): %.2f п.п.%n", maxErr);
            // 4. цепочка сюзеренов и типы отношений
            TitleData.Title north = td.findTitle("Королевство Севера");
            TitleData.Realm nr = td.findRealm("Королевство Севера");
            System.out.print("Королевство Севера: сюзерены: ");
            for (TitleData.Realm r : td.overlordChain(nr)) System.out.print(r.name + (r.overlord != null ? " [" + td.relationLabel(r.relation) + "] -> " : ""));
            System.out.println();
            TitleData.Realm fc = td.findRealm("Вольные города");
            System.out.println("Вольные города: " + td.relationLabel(fc.relation) + " государства " + fc.overlord.name);
            Map<String, Integer> byRel = new TreeMap<>();
            for (TitleData.Realm r : td.realmList()) if (r.relation != null) byRel.merge(td.relationLabel(r.relation), 1, Integer::sum);
            System.out.println("отношения: " + byRel);
            // 5. запрос «где я» в точке столицы Севера
            TitleData.Title prov = m.provinceAt(north.capX + 0.5, north.capZ + 0.5);
            StringBuilder sb = new StringBuilder();
            List<TitleData.Title> chain = td.chain(prov);
            for (int i = chain.size() - 1; i >= 0; i--) sb.append(td.tiers.get(chain.get(i).tier).name).append(": ").append(chain.get(i).name).append(i > 0 ? " > " : "");
            System.out.println("цепочка в столице Севера: " + sb);
            // 6. циклы
            int cyc = 0;
            for (TitleData.Realm r : td.realmList()) if (td.overlordChain(r).size() >= 16) cyc++;
            System.out.println("циклов подчинения: " + cyc);
        }
    }
}
