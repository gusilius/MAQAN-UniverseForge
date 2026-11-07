package dev.worldforge.mod;

import java.io.*;
import java.nio.file.*;
import java.util.*;

public class MapTest {
    static int fails = 0;
    static void check(boolean ok, String what) { System.out.println((ok ? "ok   " : "FAIL ") + what); if (!ok) fails++; }

    public static void main(String[] a) throws Exception {
        String ns = a[1].split(":")[0], nm = a[1].split(":")[1];
        MapData m = MapData.load("t", name -> {
            try (var zf = new java.util.zip.ZipFile(a[0])) {
                var e = zf.getEntry("data/" + ns + "/worldforge_maps/" + nm + "/" + name);
                return e == null ? null : zf.getInputStream(e).readAllBytes();
            }
        });
        long t0 = System.currentTimeMillis();
        int[] terrain = TerrainImage.render(m);
        System.out.println("рельеф: " + (System.currentTimeMillis() - t0) + " мс");
        Path out = Paths.get(a[2]);
        Files.createDirectories(out);
        for (String mode : MapImages.modes(m.titles)) {
            t0 = System.currentTimeMillis();
            int[] img = MapImages.render(m.titles, terrain, mode);
            try (OutputStream os = Files.newOutputStream(out.resolve(mode + ".png"))) { MapImages.writePng(img, m.w, m.h, os); }
            System.out.println("режим " + mode + ": " + (System.currentTimeMillis() - t0) + " мс");
        }
        // подсветка
        TitleData d = m.titles;
        TitleData.Title north = d.findTitle("Королевство Севера");
        MapImages.Overlay ov = MapImages.overlay(d, "kingdom", north.id);
        check(ov != null && ov.w > 100 && ov.h > 100, "подсветка королевства " + (ov == null ? "" : ov.w + "x" + ov.h));
        int cx = (int) (north.capNx * d.w), cz = (int) (north.capNy * d.h);
        check(MapImages.labelAt(d, "kingdom", cx, cz) == north.id, "метка королевства в столице");
        // синхронизация
        byte[] full = MapSync.encodeState(m, 7);
        MapSync.State st = MapSync.decodeState(full);
        check(st.version == 7 && st.w == m.w && st.titles.titlesJson().equals(d.titlesJson()) && st.titles.realmsJson().equals(d.realmsJson())
                && Arrays.equals(st.titles.pixels(), d.pixels()), "полное состояние: " + full.length / 1024 + " КБ, туда-обратно идентично");
        TitleEditor ed = new TitleEditor(d);
        short[] before = d.pixels().clone();
        TitleData.Title p = null, o = null;
        for (TitleData.Title t : d.allTitles()) { if (t.tier == 0 && t.neighbors.length > 0 && t.areaBlocks > 20000) { p = t; o = d.get(t.neighbors[0]); break; } }
        ed.paintAtBlock(o.capX, o.capZ, 150, p.id);
        ed.rename(p.id, "Тест");
        byte[] delta = MapSync.encodeDelta(d, before, 8);
        MapSync.applyDelta(st, delta);
        check(st.version == 8 && Arrays.equals(st.titles.pixels(), d.pixels()) && st.titles.titlesJson().equals(d.titlesJson()), "дельта: " + delta.length / 1024 + " КБ применена");
        // нарезка
        byte[] big = new byte[250_000];
        new Random(1).nextBytes(big);
        List<byte[]> parts = MapSync.split(big, 30_000);
        MapSync.Reassembler r = new MapSync.Reassembler();
        byte[] got = null;
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < parts.size(); i++) order.add(i);
        Collections.shuffle(order, new Random(2));
        for (int i : order) { byte[] x = r.add(5, i, parts.size(), parts.get(i)); if (x != null) got = x; }
        check(got != null && Arrays.equals(got, big), "нарезка " + parts.size() + " кусков и сборка в любом порядке");
        // метки
        Markers ms = new Markers();
        ms.add("Харренхол", "castle", 100, -200, 0xAA3322);
        ms.add("Харренхол", "castle", 5, 5, 0xAA3322);
        ms.add("Королевская Гавань", "port", 1, 2, 0x3366AA);
        Markers back = Markers.fromJson(ms.toJson());
        check(back.list().size() == 3 && back.find("harrenhol_2") != null && back.find("Королевская Гавань").type.equals("port"), "метки: JSON туда-обратно, ключи " + back.list().get(1).id);
        boolean threw = false;
        try { ms.add("x", "bogus", 0, 0, 0); } catch (IllegalArgumentException e) { threw = true; }
        check(threw, "неизвестный тип метки отклонён");
        System.out.println(fails == 0 ? "ВСЕ ПРОВЕРКИ ПРОЙДЕНЫ" : "ОШИБОК: " + fails);
    }
}
