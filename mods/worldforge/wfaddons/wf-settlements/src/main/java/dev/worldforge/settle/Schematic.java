package dev.worldforge.settle;

import java.io.IOException;
import java.util.*;

/**
 * Схема здания из файла: Litematica (.litematic), Sponge (.schem v2/v3) или ванильная структура (.nbt).
 * Чистая логика: даёт размеры, палитру строк состояний блоков ("minecraft:oak_door[facing=south,half=lower]")
 * и индексы блоков в порядке x + z*sx + y*sx*sz. Превращение строк в BlockState делает код с Minecraft.
 */
public final class Schematic {
    public final int sx, sy, sz;
    public final List<String> palette;
    /** Индексы в палитре; длина sx*sy*sz; порядок x + z*sx + y*sx*sz. */
    public final int[] cells;
    public final String format;

    Schematic(String format, int sx, int sy, int sz, List<String> palette, int[] cells) {
        this.format = format; this.sx = sx; this.sy = sy; this.sz = sz; this.palette = palette; this.cells = cells;
    }

    public static boolean isAir(String state) {
        String n = state.contains("[") ? state.substring(0, state.indexOf('[')) : state;
        return n.equals("minecraft:air") || n.equals("minecraft:cave_air") || n.equals("minecraft:void_air") || n.equals("minecraft:structure_void")
                || n.equals("minecraft:jigsaw") || n.equals("minecraft:structure_block");
    }

    public int index(int x, int y, int z) { return x + z * sx + y * sx * sz; }

    /** Число блоков, которые нужно положить (без воздуха). */
    public int solidCount() {
        boolean[] air = airFlags();
        int n = 0;
        for (int c : cells) if (!air[c]) n++;
        return n;
    }

    /** Сколько каких блоков нужно: id блока (без свойств) -> количество. */
    public Map<String, Integer> blockCounts() {
        boolean[] air = airFlags();
        int[] cnt = new int[palette.size()];
        for (int c : cells) if (!air[c]) cnt[c]++;
        Map<String, Integer> m = new TreeMap<>();
        for (int i = 0; i < cnt.length; i++) {
            if (cnt[i] == 0) continue;
            String p = palette.get(i);
            m.merge(p.contains("[") ? p.substring(0, p.indexOf('[')) : p, cnt[i], Integer::sum);
        }
        return m;
    }

    /** Сколько блоков (не воздуха), чьё полное состояние содержит подстроку, например "_bed" и "part=head". */
    public int countMatching(String... parts) {
        boolean[] air = airFlags();
        int[] cnt = new int[palette.size()];
        for (int c : cells) cnt[c]++;
        int n = 0;
        outer:
        for (int i = 0; i < cnt.length; i++) {
            if (air[i]) continue;
            for (String p : parts) if (!palette.get(i).contains(p)) continue outer;
            n += cnt[i];
        }
        return n;
    }

    private boolean[] airFlags() {
        boolean[] a = new boolean[palette.size()];
        for (int i = 0; i < a.length; i++) a[i] = isAir(palette.get(i));
        return a;
    }

    public static Schematic parse(byte[] data, String fileName) throws IOException {
        Map<String, Object> root = Nbt.read(data);
        String n = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        if (root.containsKey("Regions")) return litematic(root);
        if (root.containsKey("Schematic") || root.containsKey("BlockData") || (root.containsKey("Palette") && root.containsKey("Width"))) return sponge(root);
        if (root.containsKey("blocks") && root.containsKey("size")) return vanilla(root);
        throw new IOException("Не похоже на схему (" + n + "): неизвестный формат");
    }

    // ------------------------------------------------------------------ Litematica

    @SuppressWarnings("unchecked")
    static Schematic litematic(Map<String, Object> root) throws IOException {
        Map<String, Object> regions = Nbt.map(root, "Regions");
        if (regions == null || regions.isEmpty()) throw new IOException("litematic: нет регионов");
        record R(int ox, int oy, int oz, int w, int h, int l, List<String> pal, long[] bits) {}
        List<R> list = new ArrayList<>();
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (Object o : regions.values()) {
            Map<String, Object> r = (Map<String, Object>) o;
            Map<String, Object> pos = Nbt.map(r, "Position"), size = Nbt.map(r, "Size");
            if (pos == null || size == null) throw new IOException("litematic: у региона нет Position/Size");
            int px = Nbt.num(pos, "x", 0), py = Nbt.num(pos, "y", 0), pz = Nbt.num(pos, "z", 0);
            int sx = Nbt.num(size, "x", 0), sy = Nbt.num(size, "y", 0), sz = Nbt.num(size, "z", 0);
            int w = Math.abs(sx), h = Math.abs(sy), l = Math.abs(sz);
            if (w == 0 || h == 0 || l == 0) continue;
            int ox = px + (sx < 0 ? sx + 1 : 0), oy = py + (sy < 0 ? sy + 1 : 0), oz = pz + (sz < 0 ? sz + 1 : 0);
            List<String> pal = new ArrayList<>();
            Object pl = r.get("BlockStatePalette");
            if (!(pl instanceof List)) throw new IOException("litematic: нет палитры");
            for (Object e : (List<Object>) pl) pal.add(stateString((Map<String, Object>) e));
            if (!(r.get("BlockStates") instanceof long[] bits)) throw new IOException("litematic: нет BlockStates");
            list.add(new R(ox, oy, oz, w, h, l, pal, bits));
            minX = Math.min(minX, ox); minY = Math.min(minY, oy); minZ = Math.min(minZ, oz);
            maxX = Math.max(maxX, ox + w - 1); maxY = Math.max(maxY, oy + h - 1); maxZ = Math.max(maxZ, oz + l - 1);
        }
        if (list.isEmpty()) throw new IOException("litematic: регионы пусты");
        int sx = maxX - minX + 1, sy = maxY - minY + 1, sz = maxZ - minZ + 1;
        if ((long) sx * sy * sz > 8_000_000L) throw new IOException("Схема слишком большая: " + sx + "x" + sy + "x" + sz);
        List<String> palette = new ArrayList<>();
        Map<String, Integer> pIndex = new HashMap<>();
        palette.add("minecraft:air");
        pIndex.put("minecraft:air", 0);
        int[] cells = new int[sx * sy * sz];
        for (R r : list) {
            int bitsPer = Math.max(2, 32 - Integer.numberOfLeadingZeros(r.pal.size() - 1));
            long mask = (1L << bitsPer) - 1;
            int[] map = new int[r.pal.size()];
            for (int i = 0; i < map.length; i++) map[i] = pIndex.computeIfAbsent(r.pal.get(i), k -> { palette.add(k); return palette.size() - 1; });
            long total = (long) r.w * r.h * r.l;
            if ((total * bitsPer + 63) / 64 > r.bits.length) throw new IOException("litematic: BlockStates короче, чем нужно");
            for (int y = 0; y < r.h; y++) {
                for (int z = 0; z < r.l; z++) {
                    for (int x = 0; x < r.w; x++) {
                        long idx = (long) y * r.l * r.w + (long) z * r.w + x;
                        long bit = idx * bitsPer;
                        int w0 = (int) (bit >>> 6), off = (int) (bit & 63);
                        long v = r.bits[w0] >>> off;
                        if (off + bitsPer > 64) v |= r.bits[w0 + 1] << (64 - off);
                        int pi = (int) (v & mask);
                        if (pi >= map.length) continue;
                        int cx = r.ox - minX + x, cy = r.oy - minY + y, cz = r.oz - minZ + z;
                        int dst = cx + cz * sx + cy * sx * sz;
                        if (map[pi] != 0 || cells[dst] == 0) cells[dst] = map[pi];
                    }
                }
            }
        }
        return new Schematic("litematic", sx, sy, sz, palette, cells);
    }

    @SuppressWarnings("unchecked")
    private static String stateString(Map<String, Object> e) {
        String name = String.valueOf(e.get("Name"));
        Object props = e.get("Properties");
        if (!(props instanceof Map) || ((Map<?, ?>) props).isEmpty()) return name;
        StringBuilder sb = new StringBuilder(name).append('[');
        boolean first = true;
        for (Map.Entry<String, Object> p : new TreeMap<>((Map<String, Object>) props).entrySet()) {
            if (!first) sb.append(',');
            first = false;
            sb.append(p.getKey()).append('=').append(p.getValue());
        }
        return sb.append(']').toString();
    }

    // ------------------------------------------------------------------ Sponge

    @SuppressWarnings("unchecked")
    static Schematic sponge(Map<String, Object> root0) throws IOException {
        Map<String, Object> root = root0.get("Schematic") instanceof Map ? (Map<String, Object>) root0.get("Schematic") : root0;
        int w = Nbt.num(root, "Width", 0), h = Nbt.num(root, "Height", 0), l = Nbt.num(root, "Length", 0);
        if (w <= 0 || h <= 0 || l <= 0) throw new IOException("schem: нет размеров");
        if ((long) w * h * l > 8_000_000L) throw new IOException("Схема слишком большая: " + w + "x" + h + "x" + l);
        Map<String, Object> pal = Nbt.map(root, "Palette");
        Object data = root.get("BlockData");
        if (root.get("Blocks") instanceof Map) {
            Map<String, Object> blocks = (Map<String, Object>) root.get("Blocks");
            pal = Nbt.map(blocks, "Palette");
            data = blocks.get("Data");
        }
        if (pal == null || !(data instanceof byte[] bytes)) throw new IOException("schem: нет Palette/BlockData");
        List<String> palette = new ArrayList<>();
        int max = -1;
        for (Object v : pal.values()) max = Math.max(max, ((Number) v).intValue());
        String[] arr = new String[max + 1];
        for (Map.Entry<String, Object> e : pal.entrySet()) arr[((Number) e.getValue()).intValue()] = e.getKey();
        for (String s : arr) palette.add(s == null ? "minecraft:air" : s);
        int[] cells = new int[w * h * l];
        int pos = 0, i = 0;
        while (i < cells.length) {
            if (pos >= bytes.length) throw new IOException("schem: BlockData короче, чем нужно");
            int v = 0, shift = 0, b;
            do {
                b = bytes[pos++] & 0xFF;
                v |= (b & 0x7F) << shift;
                shift += 7;
                if (shift > 35) throw new IOException("schem: плохой varint");
            } while ((b & 0x80) != 0 && pos < bytes.length);
            if (v >= palette.size()) v = 0;
            cells[i++] = v;
        }
        return new Schematic("schem", w, h, l, palette, cells);
    }

    // ------------------------------------------------------------------ ванильная структура

    @SuppressWarnings("unchecked")
    static Schematic vanilla(Map<String, Object> root) throws IOException {
        List<Object> size = (List<Object>) root.get("size");
        int w = ((Number) size.get(0)).intValue(), h = ((Number) size.get(1)).intValue(), l = ((Number) size.get(2)).intValue();
        if (w <= 0 || h <= 0 || l <= 0 || (long) w * h * l > 8_000_000L) throw new IOException("nbt: плохие размеры");
        Object pl = root.get("palette");
        if (pl == null && root.get("palettes") instanceof List<?> ps && !ps.isEmpty()) pl = ps.get(0);
        if (!(pl instanceof List)) throw new IOException("nbt: нет палитры");
        List<String> palette = new ArrayList<>();
        for (Object e : (List<Object>) pl) palette.add(stateString((Map<String, Object>) e));
        int air = palette.indexOf("minecraft:air");
        if (air < 0) { palette.add("minecraft:air"); air = palette.size() - 1; }
        int[] cells = new int[w * h * l];
        Arrays.fill(cells, air);
        for (Object o : (List<Object>) root.get("blocks")) {
            Map<String, Object> b = (Map<String, Object>) o;
            List<Object> p = (List<Object>) b.get("pos");
            int x = ((Number) p.get(0)).intValue(), y = ((Number) p.get(1)).intValue(), z = ((Number) p.get(2)).intValue();
            int s = Nbt.num(b, "state", air);
            if (x < 0 || y < 0 || z < 0 || x >= w || y >= h || z >= l || s < 0 || s >= palette.size()) continue;
            cells[x + z * w + y * w * l] = s;
        }
        // воздух должен иметь индекс 0 не обязательно: проверки идут по имени
        return new Schematic("nbt", w, h, l, palette, cells);
    }
}
