package dev.worldforge.mod;

import java.util.*;

/** Режимы по свойствам (культура, религия): ключи, цвета, рендер. Аргументы: датапак, ns:имя. */
public class PropTest {
    public static void main(String[] a) throws Exception {
        String ns = a[1].split(":")[0], nm = a[1].split(":")[1];
        MapData m = MapData.load("t", name -> {
            try (var zf = new java.util.zip.ZipFile(a[0])) {
                var e = zf.getEntry("data/" + ns + "/worldforge_maps/" + nm + "/" + name);
                return e == null ? null : zf.getInputStream(e).readAllBytes();
            }
        });
        TitleData d = m.titles;
        List<String> modes = MapImages.modes(d);
        System.out.println("режимы " + modes);
        if (!modes.contains("p:culture") || !modes.contains("p:religion")) throw new AssertionError("нет режимов свойств");
        MapImages.PropInfo pi = MapImages.propInfo(d, "culture");
        for (int i = 0; i < pi.vals.size(); i++) System.out.printf("%s #%06x %d%n", pi.vals.get(i), pi.rgb[i], pi.area[i]);
        int[] terr = new int[d.w * d.h];
        Arrays.fill(terr, 0xFF405060);
        long t0 = System.currentTimeMillis();
        int[] img = MapImages.render(d, terr, "p:culture");
        System.out.println("render мс " + (System.currentTimeMillis() - t0));
        int diff = 0;
        for (int v : img) if (v != 0xFF405060) diff++;
        if (diff < 1000) throw new AssertionError("заливка пуста");
        int lab = 0;
        for (int z = 0; z < d.h && lab == 0; z++) for (int x = 0; x < d.w && lab == 0; x++) lab = MapImages.labelAt(d, "p:culture", x, z);
        if (lab <= 0) throw new AssertionError("labelAt");
        if (MapImages.overlay(d, "p:culture", lab) == null) throw new AssertionError("overlay");
        System.out.println("PROP OK");
    }
}
