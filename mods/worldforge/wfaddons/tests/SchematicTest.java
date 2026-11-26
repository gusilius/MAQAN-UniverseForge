package dev.worldforge.tests;

import dev.worldforge.settle.*;

import java.io.*;
import java.util.*;
import java.util.zip.GZIPOutputStream;

public class SchematicTest {
    static int fails;
    static void ok(boolean c, String w) { System.out.println((c ? "ok   " : "FAIL ") + w); if (!c) fails++; }

    // ---- крошечный писатель NBT для тестов
    static void name(DataOutputStream d, int type, String n) throws IOException { d.writeByte(type); d.writeUTF(n); }
    static void cInt(DataOutputStream d, String n, int v) throws IOException { name(d, 3, n); d.writeInt(v); }
    static void cStr(DataOutputStream d, String n, String v) throws IOException { name(d, 8, n); d.writeUTF(v); }
    static void begin(DataOutputStream d, String n) throws IOException { name(d, 10, n); }
    static void end(DataOutputStream d) throws IOException { d.writeByte(0); }
    static byte[] gz(ByteArrayOutputStream b) throws IOException { ByteArrayOutputStream o = new ByteArrayOutputStream(); try (GZIPOutputStream g = new GZIPOutputStream(o)) { g.write(b.toByteArray()); } return o.toByteArray(); }

    static void pos(DataOutputStream d, String n, int x, int y, int z) throws IOException { begin(d, n); cInt(d, "x", x); cInt(d, "y", y); cInt(d, "z", z); end(d); }

    static byte[] litematic(int w, int h, int l, int regionX, boolean negative) throws IOException {
        // палитра: 0 воздух, 1 камень, 2 дверь[facing=south]; пол из камня на y=0, дверь в (1,1,0)
        int[] idx = new int[w * h * l];
        for (int x = 0; x < w; x++) for (int z = 0; z < l; z++) idx[x + z * w] = 1;
        idx[1 + 0 * w + 1 * w * l] = 2;
        int bits = 2;
        long[] arr = new long[(int) (((long) idx.length * bits + 63) / 64)];
        for (int i = 0; i < idx.length; i++) {
            long bit = (long) i * bits; int w0 = (int) (bit >>> 6), off = (int) (bit & 63);
            arr[w0] |= ((long) idx[i]) << off;
            if (off + bits > 64) arr[w0 + 1] |= ((long) idx[i]) >>> (64 - off);
        }
        ByteArrayOutputStream b = new ByteArrayOutputStream(); DataOutputStream d = new DataOutputStream(b);
        begin(d, "");
        cInt(d, "Version", 6);
        begin(d, "Regions");
        begin(d, "main");
        pos(d, "Position", negative ? regionX + w - 1 : regionX, 0, 0);
        pos(d, "Size", negative ? -w : w, h, l);
        name(d, 9, "BlockStatePalette"); d.writeByte(10); d.writeInt(3);
        cStr(d, "Name", "minecraft:air"); end(d);
        cStr(d, "Name", "minecraft:stone"); end(d);
        cStr(d, "Name", "minecraft:oak_door"); begin(d, "Properties"); cStr(d, "half", "lower"); cStr(d, "facing", "south"); end(d); end(d);
        name(d, 12, "BlockStates"); d.writeInt(arr.length); for (long v : arr) d.writeLong(v);
        end(d); end(d); end(d);
        return gz(b);
    }

    static byte[] sponge(int w, int h, int l, boolean v3) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream(); DataOutputStream d = new DataOutputStream(b);
        begin(d, v3 ? "" : "Schematic");
        if (v3) begin(d, "Schematic");
        cInt(d, "Width", w); cInt(d, "Height", h); cInt(d, "Length", l);
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        for (int i = 0; i < w * h * l; i++) { int v = i % 3 == 0 ? 0 : 1; if (i == 130) v = 200; data.write(0); if (false) data.write(v); }
        data.reset();
        for (int y = 0; y < h; y++) for (int z = 0; z < l; z++) for (int x = 0; x < w; x++) {
            int v = y == 0 ? 1 : 0; if (x == 1 && y == 1 && z == 0) v = 130; // 130 -> двухбайтовый varint
            if (v >= 128) { data.write((v & 0x7F) | 0x80); data.write(v >> 7); } else data.write(v);
        }
        if (v3) {
            begin(d, "Blocks");
            begin(d, "Palette"); cInt(d, "minecraft:air", 0); cInt(d, "minecraft:stone", 1); cInt(d, "minecraft:glass", 130); end(d);
            name(d, 7, "Data"); d.writeInt(data.size()); d.write(data.toByteArray());
            end(d); end(d); end(d);
        } else {
            begin(d, "Palette"); cInt(d, "minecraft:air", 0); cInt(d, "minecraft:stone", 1); cInt(d, "minecraft:glass", 130); end(d);
            name(d, 7, "BlockData"); d.writeInt(data.size()); d.write(data.toByteArray());
            end(d);
        }
        return gz(b);
    }

    public static void main(String[] a) throws Exception {
        Schematic s = Schematic.parse(litematic(4, 3, 5, 10, false), "h.litematic");
        ok(s.sx == 4 && s.sy == 3 && s.sz == 5, "litematic: размеры " + s.sx + "x" + s.sy + "x" + s.sz);
        ok(s.solidCount() == 4 * 5 + 1, "litematic: блоков " + s.solidCount());
        ok(s.palette.get(s.cells[s.index(1, 1, 0)]).equals("minecraft:oak_door[facing=south,half=lower]"), "litematic: дверь со свойствами по порядку: " + s.palette.get(s.cells[s.index(1, 1, 0)]));
        ok(s.blockCounts().get("minecraft:stone") == 20, "litematic: счёт камня");

        // регион с отрицательным размером даёт тот же результат
        Schematic n = Schematic.parse(litematic(4, 3, 5, 10, true), "n.litematic");
        ok(Arrays.equals(s.cells, n.cells) && n.sx == 4, "litematic: отрицательный Size эквивалентен");

        // длинная схема: проверка пересечения границ слов в битовом массиве (палитра из 3 -> 2 бита; 3 бита не используется, но 33x... даёт много слов)
        Schematic big = Schematic.parse(litematic(33, 7, 9, 0, false), "big.litematic");
        ok(big.solidCount() == 33 * 9 + 1, "litematic: большая схема " + big.solidCount());

        for (boolean v3 : new boolean[]{false, true}) {
            Schematic p = Schematic.parse(sponge(3, 2, 2, v3), "x.schem");
            ok(p.sx == 3 && p.sy == 2 && p.sz == 2, "sponge v" + (v3 ? 3 : 2) + ": размеры");
            ok(p.palette.get(p.cells[p.index(1, 1, 0)]).equals("minecraft:glass"), "sponge v" + (v3 ? 3 : 2) + ": varint 130");
            ok(p.solidCount() == 3 * 2 + 1, "sponge v" + (v3 ? 3 : 2) + ": блоков " + p.solidCount());
        }

        boolean bad = false;
        try { Schematic.parse(new byte[]{1, 2, 3, 4}, "junk.bin"); } catch (IOException e) { bad = true; }
        ok(bad, "мусор -> IOException");
        ok(Schematic.isAir("minecraft:cave_air") && !Schematic.isAir("minecraft:stone[x=1]"), "isAir");
        // стоимость и описание здания
        Defs defs = new Defs();
        defs.add("{\"resources\":{\"wood\":\"Дерево\",\"stone\":\"Камень\",\"food\":\"Еда\"}}");
        defs.add(SchemCost.buildingJson("My House!", s, null));
        Defs.Building b = defs.buildings.get("schem_my_house");
        ok(b != null && b.category.equals("schem") && b.blueprint.equals("schem:My House!"), "здание из схемы создано: " + (b == null ? "null" : b.id));
        ok(b != null && b.cost.getOrDefault("stone", 0) == 3 && b.cost.getOrDefault("wood", 0) == 1, "стоимость " + (b == null ? "" : b.cost));
        ok(b != null && b.work == 20 && b.sizeX == 4 && b.sizeZ == 5, "работа и размер");
        defs.add(SchemCost.buildingJson("Tower", s, "{\"name\":\"Башня\",\"category\":\"military\",\"cost\":{\"stone\":50},\"housing\":2}"));
        Defs.Building t = defs.buildings.get("schem_tower");
        ok(t.name.equals("Башня") && t.category.equals("military") && t.cost.get("stone") == 50 && t.housing == 2 && t.blueprint.equals("schem:Tower"), "файл-спутник перекрывает поля");
        ok(defs.validate().stream().noneMatch(x -> !x.startsWith("Предупреждение")), "валидация " + defs.validate());
        ok(SchemCost.idFor("Дом №1").equals("schem_дом_1") && SchemCost.idFor("a-b c").equals("schem_a_b_c"), "id из имени файла");

        System.out.println(fails == 0 ? "ALL OK" : "FAILS: " + fails);
        System.exit(fails == 0 ? 0 : 1);
    }
}
