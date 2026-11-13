package dev.worldforge.mod;

import java.util.HashSet;
import java.util.Set;

/**
 * Рисует политическую карту в формате карты-предмета Minecraft (128x128 байт, палитра MapColor) по текущим титулам.
 * Повторяет логику инструмента worldforge (titles_out._mapitem). Узоры подчинённых упрощены до шахматной сетки.
 */
public final class PoliticalMapRenderer {
    private static final int[] MAPCOLOR = {0, 0x7FB238, 0xF7E9A3, 0xC7C7C7, 0xFF0000, 0xA0A0FF, 0xA7A7A7, 0x007C00, 0xFFFFFF,
            0xA4A8B8, 0x976D4D, 0x707070, 0x4040FF, 0x8F7748, 0xFFFCF5, 0xD87F33, 0xB24CD8, 0x6699D8, 0xE5E533, 0x7FCC19,
            0xF27FA5, 0x4C4C4C, 0x999999, 0x4C7F99, 0x7F3FB2, 0x334CB2, 0x664C33, 0x667F33, 0x993333, 0x191919, 0xFAEE4D,
            0x5CDBD5, 0x4A80FF, 0x00D93A, 0x815631, 0x700200, 0xD1B1A1, 0x9F5224, 0x95576C, 0x706C8A, 0xBA8524, 0x677535,
            0xA04D4E, 0x392923, 0x876B62, 0x575C5C, 0x7A4958, 0x4C3E5C, 0x4C3223, 0x4C522A, 0x8E3C2E, 0x251610, 0xBD3031,
            0x943F61, 0x5C191D, 0x167E86, 0x3A8E8C, 0x562C3E, 0x14B485, 0x646464, 0xD8AF93, 0x7FA796};
    private static final int[] SHADE = {180, 220, 255, 135};
    static final int CODE_WATER = 12 * 4, CODE_LAND = 22 * 4 + 1, CODE_DOT = 29 * 4 + 1;

    private PoliticalMapRenderer() {}

    static int codeRgb(int code) {
        int v = MAPCOLOR[code >> 2], m = SHADE[code & 3];
        return (((v >> 16 & 255) * m / 255) << 16) | (((v >> 8 & 255) * m / 255) << 8) | ((v & 255) * m / 255);
    }

    private static long dist2(int a, int b) {
        long dr = (a >> 16 & 255) - (b >> 16 & 255), dg = (a >> 8 & 255) - (b >> 8 & 255), db = (a & 255) - (b & 255);
        return dr * dr + dg * dg + db * db;
    }

    /** Ближайший код палитры к цвету, не входящий в used (если все заняты, ближайший вообще). */
    static int nearestCode(int rgb, Set<Integer> used) {
        int best = -1, bestAny = -1;
        long bd = Long.MAX_VALUE, bdAny = Long.MAX_VALUE;
        for (int b = 1; b < MAPCOLOR.length; b++) {
            for (int s = 0; s < 4; s++) {
                int code = (b << 2) | s;
                long dd = dist2(codeRgb(code), rgb);
                if (dd < bdAny) { bdAny = dd; bestAny = code; }
                if (dd < bd && !used.contains(code)) { bd = dd; best = code; }
            }
        }
        return best >= 0 ? best : bestAny;
    }

    /** Пересоздаёт все карты-предметы (по уровням и государств) в m.mapItems. */
    public static void regenerateAll(MapData m) {
        if (m.titles == null) return;
        for (TitleData.Tier t : m.titles.tiers) m.mapItems.put(t.key, render(m, t.key));
        m.mapItems.put("realms", render(m, "realms"));
    }

    /** level: ключ уровня (например "kingdom") или "realms". */
    public static byte[] render(MapData m, String level) {
        TitleData d = m.titles;
        int w = d.w, h = d.h, side0 = Math.max(w, h), k = (side0 + 127) / 128, side = 128 * k;
        int ox = (side - w) / 2, oy = (side - h) / 2, c = k / 2;
        boolean realms = level.equals("realms");
        int tierIdx = -1;
        for (TitleData.Tier t : d.tiers) if (t.key.equals(level)) tierIdx = t.index;
        if (!realms && tierIdx < 0) throw new IllegalArgumentException("Нет уровня " + level);
        int[] lut = realms ? d.realmLut() : d.provinceLut(tierIdx);
        int labels = realms ? d.realmList().size() : d.maxId();

        // коды цветов по меткам
        int[] codes = new int[labels + 1];
        Set<Integer> used = new HashSet<>();
        if (realms) {
            for (int i = 1; i <= labels; i++) {
                codes[i] = nearestCode(d.realmList().get(i - 1).rgb, used);
                used.add(codes[i]);
            }
        } else {
            boolean top = tierIdx == d.tiers.size() - 1;
            for (TitleData.Title t : d.allTitles()) {
                if (t.tier != tierIdx) continue;
                codes[t.id] = nearestCode(t.rgb, top ? used : new HashSet<>());
                used.add(codes[t.id]);
            }
        }

        int[] cell = new int[128 * 128];
        byte[] img = new byte[128 * 128];
        short[] px = d.pixels();
        for (int gy = 0; gy < 128; gy++) {
            for (int gx = 0; gx < 128; gx++) {
                int sx = gx * k + c - ox, sz = gy * k + c - oy, lab = 0;
                if (sx >= 0 && sz >= 0 && sx < w && sz < h) {
                    int p = px[sz * w + sx] & 0xFFFF;
                    lab = p == 0 || p >= lut.length ? 0 : lut[p];
                }
                cell[gy * 128 + gx] = lab;
                int landCount = 0;
                for (int dz = 0; dz < k; dz++) {
                    int z = gy * k + dz - oy;
                    if (z < 0 || z >= h) continue;
                    for (int dx = 0; dx < k; dx++) {
                        int x = gx * k + dx - ox;
                        if (x >= 0 && x < w && px[z * w + x] != 0) landCount++;
                    }
                }
                boolean isl = landCount > 0.4 * k * k;
                img[gy * 128 + gx] = (byte) (lab > 0 ? codes[lab] : (isl ? CODE_LAND : CODE_WATER));
            }
        }
        if (realms) {
            for (int gy = 0; gy < 128; gy++) {
                for (int gx = 0; gx < 128; gx++) {
                    int lab = cell[gy * 128 + gx];
                    if (lab == 0 || ((gy + gx) & 1) != 0) continue;
                    TitleData.Realm r = d.realmList().get(lab - 1);
                    if (r.overlord == null) continue;
                    int oi = d.realmList().indexOf(r.overlord) + 1;
                    if (oi > 0) img[gy * 128 + gx] = (byte) codes[oi];
                }
            }
        }
        boolean[] brd = new boolean[128 * 128];
        for (int gy = 0; gy < 128; gy++) {
            for (int gx = 0; gx < 128; gx++) {
                int a = cell[gy * 128 + gx];
                if (a <= 0) continue;
                if (gx < 127 && a != cell[gy * 128 + gx + 1]) brd[gy * 128 + gx] = true;
                if (gy < 127 && a != cell[(gy + 1) * 128 + gx]) brd[gy * 128 + gx] = true;
            }
        }
        for (int i = 0; i < brd.length; i++) if (brd[i]) img[i] = (byte) (((img[i] & 0xFF) & 0xFC) | 3);
        // точки столиц (для уровней выше баронств и для государств)
        if (realms) {
            for (TitleData.Realm r : d.realmList()) {
                TitleData.Title top = null;
                for (String key : r.titleKeys) {
                    TitleData.Title t = d.byKey(key);
                    if (t != null && (top == null || t.tier > top.tier)) top = t;
                }
                if (top != null) dot(img, top, w, h, ox, oy, side);
                else if (Boolean.TRUE.equals(r.props.get("emergence.virtual"))) {
                    // Emergent countries have no fabricated raster provinces. Their actual
                    // materialized settlements are shown as map markers at their Minecraft centers.
                    Object rawCenters = r.props.get("emergence.settlement_centers");
                    if (rawCenters != null) for (String center : String.valueOf(rawCenters).split(";")) {
                        int at = center.lastIndexOf('@'), comma = center.indexOf(',', at + 1);
                        if (at < 0 || comma < 0) continue;
                        try {
                            double worldX = Double.parseDouble(center.substring(at + 1, comma));
                            double worldZ = Double.parseDouble(center.substring(comma + 1));
                            double pixelX = worldX / Math.max(0.001, m.bpp) + w * 0.5;
                            double pixelZ = worldZ / Math.max(0.001, m.bpp) + h * 0.5;
                            dot(img, pixelX / w, pixelZ / h, w, h, ox, oy, side);
                        } catch (NumberFormatException ignored) { }
                    }
                }
            }
        } else if (tierIdx > 0) {
            for (TitleData.Title t : d.allTitles()) if (t.tier == tierIdx) dot(img, t, w, h, ox, oy, side);
        }
        return img;
    }

    private static void dot(byte[] img, TitleData.Title t, int w, int h, int ox, int oy, int side) {
        dot(img, t.capNx, t.capNy, w, h, ox, oy, side);
    }

    private static void dot(byte[] img, double nx, double ny, int w, int h, int ox, int oy, int side) {
        int gx = (int) Math.floor((nx * w + ox) / (double) side * 128);
        int gy = (int) Math.floor((ny * h + oy) / (double) side * 128);
        img[Math.min(127, Math.max(0, gy)) * 128 + Math.min(127, Math.max(0, gx))] = (byte) CODE_DOT;
    }
}
