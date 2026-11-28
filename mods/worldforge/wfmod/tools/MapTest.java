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
        int terrainHash = Arrays.hashCode(terrain);
        int[] terrainRepeat = TerrainImage.render(m);
        check(Arrays.equals(terrain, terrainRepeat), "географическая картография детерминирована между рендерами");
        check(Arrays.hashCode(terrain) == terrainHash, "географический растр не меняется при повторном рендере");
        check(Arrays.stream(terrain).distinct().limit(2).count() > 1, "географическая карта содержит фактические различия рельефа/биомов");
        boolean shortTerrainRejected = false;
        try { MapImages.render(m.titles, new int[Math.max(0, terrain.length - 1)], MapImages.REALMS); }
        catch (IllegalArgumentException expected) { shortTerrainRejected = true; }
        check(shortTerrainRejected, "политический рендерер отклоняет неполный массив рельефа");
        boolean nullTerrainRejected = false;
        try { MapImages.render(m.titles, null, MapImages.TERRAIN); }
        catch (IllegalArgumentException expected) { nullTerrainRejected = true; }
        check(nullTerrainRejected, "географический рендерер отклоняет отсутствующий массив рельефа");
        boolean emptyModeRejected = false;
        try { MapImages.render(m.titles, terrain, " "); }
        catch (IllegalArgumentException expected) { emptyModeRejected = true; }
        check(emptyModeRejected, "пустой режим карты отклоняется контролируемой ошибкой");
        boolean badPngRejected = false;
        try { MapImages.writePng(new int[3], 2, 2, new ByteArrayOutputStream()); }
        catch (IllegalArgumentException expected) { badPngRejected = true; }
        check(badPngRejected, "экспорт PNG отклоняет несоответствие размера пиксельного массива");
        boolean emptyPngRejected = false;
        try { MapImages.writePng(new int[0], 0, 0, new ByteArrayOutputStream()); }
        catch (IllegalArgumentException expected) { emptyPngRejected = true; }
        check(emptyPngRejected, "экспорт PNG отклоняет нулевые размеры");
        ByteArrayOutputStream onePixelPng = new ByteArrayOutputStream();
        MapImages.writePng(new int[]{0xFFAA8855}, 1, 1, onePixelPng);
        check(onePixelPng.size() > 0, "экспорт PNG сохраняет корректное однопиксельное изображение");
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
        // Повреждённая дельта не должна частично менять уже опубликованную карту.
        short[] stablePixels = st.titles.pixels().clone();
        int stableVersion = st.version;
        ByteArrayOutputStream malformedBytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(new java.util.zip.DeflaterOutputStream(malformedBytes))) {
            out.writeInt(0x574D32); // MAGIC + 1
            out.writeInt(99);
            out.writeInt(2);
            out.writeInt(0); out.writeShort((short) 12);
            out.writeInt(0); out.writeShort((short) 34); // duplicate pixel index
            byte[] titles = d.titlesJson().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            byte[] realms = d.realmsJson().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            out.writeInt(titles.length); out.write(titles);
            out.writeInt(realms.length); out.write(realms);
        }
        boolean malformedRejected = false;
        try { MapSync.applyDelta(st, malformedBytes.toByteArray()); }
        catch (IOException expected) { malformedRejected = true; }
        check(malformedRejected && st.version == stableVersion
                && Arrays.equals(st.titles.pixels(), stablePixels),
                "повреждённая дельта отклоняется без частичного изменения состояния");

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
        boolean nullTypeRejected = false;
        try { ms.add("null-type", null, 0, 0, 0); }
        catch (IllegalArgumentException expected) { nullTypeRejected = true; }
        check(nullTypeRejected, "null-тип отклоняется контролируемой ошибкой валидации");

        check(Markers.fromJson("{broken-json").list().isEmpty(),
                "синтаксически повреждённый JSON не ломает загрузку маркеров");

        Path relativeFile = Paths.get("maqan-markers-relative-test-" + System.nanoTime() + ".json");
        try {
            Markers relativeMarkers = new Markers();
            relativeMarkers.add("Relative", "city", 1, 2, 0x123456);
            relativeMarkers.save(relativeFile);
            check(Markers.load(relativeFile).find("Relative") != null,
                    "сохранение маркеров по относительному пути без родительской папки");
        } finally {
            Files.deleteIfExists(relativeFile);
            Files.deleteIfExists(relativeFile.resolveSibling(relativeFile.getFileName() + ".tmp"));
        }

        // Explicit visual overrides survive persistence and are removed with their marker.
        Markers visualMarkers = new Markers();
        Markers.Marker commander = visualMarkers.add("Командир", "commander", 12, 34, 0xD4AF37);
        visualMarkers.setVisualProfile(commander.id, "maqan:commander.knight");
        Markers visualReloaded = Markers.fromJson(visualMarkers.toJson());
        check(visualReloaded.find(commander.id) != null
                        && "maqan:commander.knight".equals(visualReloaded.find(commander.id).visualProfile),
                "визуальный профиль командира сохраняется и восстанавливается");
        visualReloaded.remove(commander.id);
        check(!visualReloaded.toJson().contains("visual_overrides"),
                "удаление объекта очищает сохранённое визуальное переопределение");

        // Older/partially damaged marker files must not crash map loading.
        Markers missingList = Markers.fromJson("{}");
        check(missingList.list().isEmpty(), "файл меток без массива markers загружается безопасно");
        Markers malformedEntries = Markers.fromJson("{\"markers\":[null,{},"
                + "{\"id\":\"bad-color\",\"name\":\"Bad color\",\"type\":\"castle\","
                + "\"x\":\"wrong\",\"z\":7,\"color\":\"#GGGGGG\"},"
                + "{\"id\":\"good\",\"name\":\"Good\",\"type\":\"commander\","
                + "\"x\":15,\"z\":-9,\"color\":\"#AABBCC\"}]}");
        Markers.Marker safe = malformedEntries.find("good");
        Markers.Marker fallback = malformedEntries.find("bad-color");
        check(malformedEntries.list().size() == 2 && safe != null && safe.x == 15 && safe.z == -9
                        && fallback != null && fallback.rgb == 0xFFFFFF && fallback.x == 0,
                "частично повреждённые записи пропускаются или восстанавливаются безопасными значениями");
        check(Markers.TYPES.containsAll(List.of("commander", "corps", "unit", "fort")),
                "типы военных и укреплённых объектов доступны в общем каталоге типов");
        System.out.println(fails == 0 ? "ВСЕ ПРОВЕРКИ ПРОЙДЕНЫ" : "ОШИБОК: " + fails);
    }
}
