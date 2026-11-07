package dev.worldforge.mod;

import java.nio.file.*;
import java.util.*;

/** Поднимает веб-карту на данных датапака, чтобы проверить в браузере. */
public class WebTest {
    public static void main(String[] a) throws Exception {
        String ns = a[1].split(":")[0], nm = a[1].split(":")[1];
        MapData m = MapData.load("t", name -> {
            try (var zf = new java.util.zip.ZipFile(a[0])) {
                var e = zf.getEntry("data/" + ns + "/worldforge_maps/" + nm + "/" + name);
                return e == null ? null : zf.getInputStream(e).readAllBytes();
            }
        });
        Markers ms = new Markers();
        ms.add("Королевская Гавань", "port", 1200, 1500, 0xFFFFFF);
        ms.add("Харренхол", "castle", 300, 600, 0xFFFFFF);
        ms.add("Староместо", "city", -1400, 2500, 0xFFFFFF);
        byte[] page = Files.readAllBytes(Paths.get(a[2]));
        WebMap w = new WebMap(new WebMap.Provider() {
            public MapData map() { return m; }
            public int version() { return 1; }
            public Markers markers() { return ms; }
            public List<WebMap.Player> players() { return List.of(new WebMap.Player("Steve", 500, 900)); }
        }, "127.0.0.1", Integer.parseInt(a[3]), () -> page);
        System.out.println("порт " + w.port());
        Thread.sleep(Long.MAX_VALUE);
    }
}
