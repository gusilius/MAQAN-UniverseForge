package dev.worldforge.mod;

import java.util.ArrayList;
import java.util.List;

/**
 * Режимы карты в духе Paradox: рельеф, провинции, графства, герцогства, королевства, империи, государства.
 * Рисует ARGB-массивы w*h; одинаково работает на сервере (веб-карта) и клиенте (экран карты). Без зависимостей от Minecraft.
 */
public final class MapImages {
    public static final String TERRAIN = "terrain", REALMS = "realms", PROP = "p:", DIP = "dip:";
    private static final int EDGE = 0x281C12;

    private MapImages() {}

    public static List<String> modes(TitleData d) {
        List<String> out = new ArrayList<>();
        out.add(TERRAIN);
        for (TitleData.Tier t : d.tiers) out.add(t.key);
        out.add(REALMS);
        out.add(DIP);                                    // дипломатический слой: цвет по отношению к выбранному государству (dip:<id>)
        for (String k : propKeys(d)) out.add(PROP + k);
        return out;
    }

    public static boolean isDip(String mode) { return mode != null && mode.startsWith(DIP); }

    public static boolean isProp(String mode) { return mode != null && mode.startsWith(PROP); }

    /** Ключи свойств титулов со скалярными значениями (культура, религия...), отсортированные. */
    public static List<String> propKeys(TitleData d) {
        java.util.TreeSet<String> ks = new java.util.TreeSet<>();
        for (TitleData.Title t : d.allTitles()) addScalarKeys(ks, t.props);
        for (TitleData.Realm r : d.realmList()) addScalarKeys(ks, r.props);
        return new ArrayList<>(ks);
    }

    private static void addScalarKeys(java.util.Set<String> ks, java.util.Map<String, Object> props) {
        for (java.util.Map.Entry<String, Object> e : props.entrySet()) {
            Object v = e.getValue();
            if (scalar(v) && !e.getKey().equals("allies") && !e.getKey().equals("enemies")) ks.add(e.getKey());   // списки id - для дипломатического слоя
        }
    }

    private static boolean scalar(Object v) {
        return v != null && !(v instanceof java.util.Map) && !(v instanceof java.util.List) && !v.toString().isEmpty();
    }

    /** Название режима свойства: из prop_defs, иначе ключ. */
    public static String propName(TitleData d, String key) {
        java.util.Map<String, Object> def = d.propDefs.get(key);
        return def != null && def.get("name") instanceof String ? (String) def.get("name") : null;
    }

    /** Значения свойства по провинциям (с наследованием вверх по иерархии), цвета и площади. Совпадает с веб-картой. */
    public static final class PropInfo {
        public String name;                 // из prop_defs (может быть null)
        public final List<String> vals = new ArrayList<>();
        public int[] lut = new int[0];      // id провинции -> номер значения (1-based), 0 = нет
        public int[] rgb = new int[0];      // по номеру значения - 1
        public long[] area = new long[0];
    }

    private static int hashStr(String s) {
        int h = 0x811C9DC5;
        for (int i = 0; i < s.length(); i++) { h ^= s.charAt(i); h *= 16777619; }
        return h;
    }

    private static int hsl(double h, double s, double l) {
        s /= 100; l /= 100;
        double a = s * Math.min(l, 1 - l);
        int[] c = new int[3];
        int[] n = {0, 8, 4};
        for (int i = 0; i < 3; i++) {
            double k = (n[i] + h / 30) % 12;
            double v = l - a * Math.max(-1, Math.min(Math.min(k - 3, 9 - k), 1));
            c[i] = (int) Math.round(v * 255);
        }
        return (c[0] << 16) | (c[1] << 8) | c[2];
    }

    public static PropInfo propInfo(TitleData d, String key) {
        PropInfo pi = new PropInfo();
        java.util.Map<String, Integer> idx = new java.util.HashMap<>();
        int[] lut = new int[d.maxId() + 1];
        java.util.List<Long> areas = new ArrayList<>();
        for (TitleData.Title p : d.allTitles()) {
            if (p.tier != 0) continue;
            String val = null;
            TitleData.Title cur = p;
            for (int g = 0; cur != null && g < 32; g++) {
                Object v = cur.props.get(key);
                if (scalar(v)) { val = v.toString(); break; }
                cur = cur.parent > 0 ? d.get(cur.parent) : null;
            }
            if (val == null) {                                       // свойство государства: действует на все его земли
                TitleData.Realm r = d.realmOf(p);
                Object v = r == null ? null : r.props.get(key);
                if (scalar(v)) val = v.toString();
            }
            if (val == null) continue;
            Integer id = idx.get(val);
            if (id == null) { pi.vals.add(val); id = pi.vals.size(); idx.put(val, id); areas.add(0L); }
            lut[p.id] = id;
            areas.set(id - 1, areas.get(id - 1) + Math.max(1, p.areaBlocks));
        }
        java.util.Map<String, Object> def = d.propDefs.get(key);
        pi.name = propName(d, key);
        // порядок значений из prop_defs.order идёт первым: так цвета и легенда стабильны
        if (def != null && def.get("order") instanceof List) {
            java.util.List<String> pre = new ArrayList<>();
            for (Object o : (List<?>) def.get("order")) if (idx.containsKey(String.valueOf(o))) pre.add(String.valueOf(o));
            java.util.List<Long> oldAreas = new ArrayList<>(areas);
            java.util.List<String> oldVals = new ArrayList<>(pi.vals);
            int[] remap = new int[oldVals.size() + 1];
            java.util.List<String> nv = new ArrayList<>(pre);
            for (String v : oldVals) if (!nv.contains(v)) nv.add(v);
            for (int i = 0; i < oldVals.size(); i++) remap[i + 1] = nv.indexOf(oldVals.get(i)) + 1;
            for (int i = 0; i < lut.length; i++) if (lut[i] > 0) lut[i] = remap[lut[i]];
            areas.clear();
            for (int i = 0; i < nv.size(); i++) areas.add(oldAreas.get(oldVals.indexOf(nv.get(i))));
            pi.vals.clear();
            pi.vals.addAll(nv);
        }
        int n = pi.vals.size();
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) order[i] = i;
        final String fk = key;
        java.util.Arrays.sort(order, (a, b) -> Integer.compareUnsigned(hashStr(fk + ":" + pi.vals.get(a)), hashStr(fk + ":" + pi.vals.get(b))));
        pi.rgb = new int[n];
        pi.area = new long[n];
        long base = Integer.toUnsignedLong(hashStr(key)) % 360;
        for (int k = 0; k < n; k++) pi.rgb[order[k]] = hsl((k * 137.508 + base) % 360, 58 + (k % 3) * 8, 48 + (k % 2) * 8);
        for (int i = 0; i < n; i++) pi.area[i] = areas.get(i);
        if (def != null && def.get("colors") instanceof java.util.Map) {          // явные цвета значений
            java.util.Map<?, ?> cm = (java.util.Map<?, ?>) def.get("colors");
            for (int i = 0; i < n; i++) {
                Object c = cm.get(pi.vals.get(i));
                if (c instanceof String cs && cs.matches("#[0-9a-fA-F]{6}")) pi.rgb[i] = Integer.parseInt(cs.substring(1), 16);
            }
        }
        pi.lut = lut;
        return pi;
    }

    private static int mix(int a, int b, double t) {
        int r = (int) (((a >> 16) & 255) * (1 - t) + ((b >> 16) & 255) * t);
        int g = (int) (((a >> 8) & 255) * (1 - t) + ((b >> 8) & 255) * t);
        int bl = (int) ((a & 255) * (1 - t) + (b & 255) * t);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    private static int mutedBase(int c) {                         // как в инструменте: приглушаем основу под заливку
        double r = (c >> 16) & 255, g = (c >> 8) & 255, b = c & 255, gray = (r + g + b) / 3;
        int rr = (int) Math.min(255, (gray * 0.45 + r * 0.55) * 0.92 + 18);
        int gg = (int) Math.min(255, (gray * 0.45 + g * 0.55) * 0.92 + 18);
        int bb = (int) Math.min(255, (gray * 0.45 + b * 0.55) * 0.92 + 18);
        return 0xFF000000 | (rr << 16) | (gg << 8) | bb;
    }

    private static boolean pattern(String kind, int x, int y) {
        if ("dots".equals(kind)) return x % 11 < 4 && y % 11 < 4;
        if ("cross".equals(kind)) return Math.floorMod(x - y, 14) < 4 || (x + y) % 14 < 4;
        return (x + y) % 14 < 7;
    }

    /** Таблица «номер провинции -> метка» режима (id титула уровня или номер государства), null для рельефа. */
    public static int[] lutFor(TitleData d, String mode) {
        if (REALMS.equals(mode) || isDip(mode)) return d.realmLut();
        if (isProp(mode)) return propInfo(d, mode.substring(2)).lut;
        int ti = tierIndex(d, mode);
        return ti < 0 ? null : d.provinceLut(ti);
    }

    /** Метка в каждом пикселе: id титула нужного уровня или номер государства (1-based), 0 = нет. */
    static int[] labels(TitleData d, String mode) {
        int[] lut;
        if (REALMS.equals(mode) || isDip(mode)) {
            lut = d.realmLut();
        } else if (isProp(mode)) {
            lut = propInfo(d, mode.substring(2)).lut;
        } else {
            int ti = tierIndex(d, mode);
            if (ti < 0) return null;
            lut = d.provinceLut(ti);
        }
        int[] lab = new int[d.w * d.h];
        short[] px = d.pixels();
        for (int i = 0; i < lab.length; i++) {
            int p = px[i] & 0xFFFF;
            lab[i] = p == 0 || p >= lut.length ? 0 : lut[p];
        }
        return lab;
    }

    static int tierIndex(TitleData d, String key) {
        for (TitleData.Tier t : d.tiers) if (t.key.equals(key)) return t.index;
        return -1;
    }

    private static void edges(int[] lab, int w, int h, boolean[] out, boolean thick) {
        for (int z = 0; z < h; z++) {
            for (int x = 0; x < w; x++) {
                int a = lab[z * w + x];
                if (x + 1 < w) {
                    int b = lab[z * w + x + 1];
                    if (a != b && (a > 0 || b > 0)) { out[z * w + x] = true; if (thick) out[z * w + x + 1] = true; }
                }
                if (z + 1 < h) {
                    int b = lab[(z + 1) * w + x];
                    if (a != b && (a > 0 || b > 0)) { out[z * w + x] = true; if (thick) out[(z + 1) * w + x] = true; }
                }
            }
        }
    }


    /** Внутреннее затемнение у границ (как в CK3): depth пикселей от границы метки, сила strength у самой линии. */
    static void innerGlow(int[] img, int[] lab, int w, int h, int depth, double strength) {
        int inf = depth + 1;
        byte[] dist = new byte[w * h];
        for (int z = 0, i = 0; z < h; z++) {
            for (int x = 0; x < w; x++, i++) {
                int a = lab[i];
                boolean b = x == 0 || z == 0 || x == w - 1 || z == h - 1
                        || lab[i - 1] != a || lab[i + 1] != a || lab[i - w] != a || lab[i + w] != a;
                dist[i] = (byte) (b ? 0 : inf);
            }
        }
        for (int z = 0, i = 0; z < h; z++) {
            for (int x = 0; x < w; x++, i++) {
                int d = dist[i];
                if (x > 0 && lab[i - 1] == lab[i]) d = Math.min(d, dist[i - 1] + 1);
                if (z > 0 && lab[i - w] == lab[i]) d = Math.min(d, dist[i - w] + 1);
                dist[i] = (byte) Math.min(d, inf);
            }
        }
        for (int z = h - 1; z >= 0; z--) {
            for (int x = w - 1; x >= 0; x--) {
                int i = z * w + x, d = dist[i];
                if (x < w - 1 && lab[i + 1] == lab[i]) d = Math.min(d, dist[i + 1] + 1);
                if (z < h - 1 && lab[i + w] == lab[i]) d = Math.min(d, dist[i + w] + 1);
                dist[i] = (byte) Math.min(d, inf);
                if (lab[i] != 0 && d < depth) {
                    double k = 1.0 - d / (double) depth;
                    img[i] = mix(img[i], EDGE, strength * k * k);
                }
            }
        }
    }

    private static void darken(int[] img, boolean[] e, double a) {
        for (int i = 0; i < img.length; i++) if (e[i]) img[i] = mix(img[i], EDGE, a);
    }

    /** terrain: основа, остальные режимы: заливка цветом титулов или государств, границы. */
    public static int[] render(TitleData d, int[] terrain, String mode) {
        int w = d.w, h = d.h;
        if (TERRAIN.equals(mode)) return terrain.clone();
        int[] lab = labels(d, mode);
        if (lab == null) throw new IllegalArgumentException("Нет режима карты " + mode);
        int[] img = new int[w * h];
        boolean dip = isDip(mode), realms = REALMS.equals(mode) || dip, prop = isProp(mode);
        PropInfo pinfo = prop ? propInfo(d, mode.substring(2)) : null;
        int n = realms ? d.realmList().size() + 1 : prop ? pinfo.vals.size() + 1 : d.maxId() + 1;
        int[] color = new int[n];
        String[] pat = new String[n];
        int[] over = new int[n];
        if (realms) {
            for (int i = 1; i < n; i++) {
                TitleData.Realm r = d.realmList().get(i - 1);
                color[i] = r.rgb;
                if (r.overlord != null && !dip) {
                    over[i] = d.realmList().indexOf(r.overlord) + 1;
                    Object p = d.relationTypes.get(r.relation) == null ? null : d.relationTypes.get(r.relation).get("pattern");
                    pat[i] = p == null ? "stripes" : p.toString();
                }
            }
        } else if (prop) {
            for (int i = 0; i < pinfo.rgb.length; i++) color[i + 1] = pinfo.rgb[i];
        } else {
            for (TitleData.Title t : d.allTitles()) color[t.id] = t.rgb;
        }
        if (dip) {                                                      // дипломатический слой: цвет = отношение к выбранному государству
            TitleData.Realm focus = d.realms.get(mode.substring(DIP.length()));
            java.util.Map<String, java.util.Set<String>> adj = dev.worldforge.mod.api.Diplomacy.adjacency(d);
            for (int i = 1; i < n && focus != null; i++) {
                TitleData.Realm r = d.realmList().get(i - 1);
                color[i] = dev.worldforge.mod.api.Diplomacy.relation(d, focus, r, adj).rgb;
            }
        }
        double alpha = realms ? 0.66 : 0.62;
        for (int z = 0, i = 0; z < h; z++) {
            for (int x = 0; x < w; x++, i++) {
                int l = lab[i], t = terrain[i];
                if (l == 0) { img[i] = t; continue; }
                int c = color[l];
                if (realms && over[l] > 0 && pattern(pat[l], x, z)) c = color[over[l]];
                img[i] = mix(mutedBase(t), c, alpha);
            }
        }
        boolean[] e = new boolean[w * h];
        if (realms) {
            int[] top = new int[w * h];
            int[] topOf = new int[n];
            for (int i = 1; i < n; i++) {
                int cur = i, g = 0;
                while (over[cur] > 0 && g++ < 32) cur = over[cur];
                topOf[i] = cur;
            }
            for (int i = 0; i < top.length; i++) top[i] = lab[i] == 0 ? 0 : topOf[lab[i]];
            innerGlow(img, lab, w, h, 3, 0.22);
            innerGlow(img, top, w, h, 7, 0.45);
            edges(lab, w, h, e, false);
            darken(img, e, 0.45);
            boolean[] e2 = new boolean[w * h];
            edges(top, w, h, e2, true);
            darken(img, e2, 0.7);
        } else if (prop) {
            innerGlow(img, lab, w, h, 4, 0.3);
            int[] low = labels(d, d.tiers.get(Math.min(1, d.tiers.size() - 1)).key);
            edges(low, w, h, e, false);
            darken(img, e, 0.22);
            e = new boolean[w * h];
            edges(lab, w, h, e, true);
            darken(img, e, 0.75);
        } else {
            int ti = tierIndex(d, mode);
            innerGlow(img, lab, w, h, ti > 0 ? 6 : 3, ti > 0 ? 0.42 : 0.25);
            if (ti > 0) {
                int[] low = labels(d, d.tiers.get(ti - 1).key);
                edges(low, w, h, e, false);
                darken(img, e, 0.35);
                e = new boolean[w * h];
            }
            edges(lab, w, h, e, true);
            darken(img, e, ti > 0 ? 0.85 : 0.6);
        }
        return img;
    }

    /** Подсветка одного титула уровня mode (или государства): полупрозрачная заливка и яркий контур в рамке bbox. */
    public static final class Overlay {
        public int x, y, w, h;
        public int[] argb;
    }

    public static Overlay overlay(TitleData d, String mode, int label) {
        int[] lab = labels(d, mode);
        if (lab == null) return null;
        int w = d.w, h = d.h, x0 = w, y0 = h, x1 = -1, y1 = -1;
        for (int z = 0; z < h; z++) {
            for (int x = 0; x < w; x++) {
                if (lab[z * w + x] != label) continue;
                x0 = Math.min(x0, x); y0 = Math.min(y0, z); x1 = Math.max(x1, x); y1 = Math.max(y1, z);
            }
        }
        if (x1 < 0) return null;
        Overlay o = new Overlay();
        o.x = x0; o.y = y0; o.w = x1 - x0 + 1; o.h = y1 - y0 + 1;
        o.argb = new int[o.w * o.h];
        for (int z = y0; z <= y1; z++) {
            for (int x = x0; x <= x1; x++) {
                if (lab[z * w + x] != label) continue;
                boolean edge = x == 0 || z == 0 || x == w - 1 || z == h - 1
                        || lab[z * w + x - 1] != label || lab[z * w + x + 1] != label || lab[(z - 1) * w + x] != label || lab[(z + 1) * w + x] != label;
                o.argb[(z - y0) * o.w + (x - x0)] = edge ? 0xFFFFF0A0 : 0x50FFFFFF;
            }
        }
        return o;
    }

    /** Метка (титул или государство) под пикселем для режима, 0 если нет. */
    public static int labelAt(TitleData d, String mode, int px, int pz) {
        int p = d.provinceIdAtPixel(px, pz);
        if (p == 0) return 0;
        int[] lut = lutFor(d, mode);
        return lut == null || p >= lut.length ? 0 : lut[p];
    }

    public static void writePng(int[] argb, int w, int h, java.io.OutputStream out) throws java.io.IOException {
        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        img.setRGB(0, 0, w, h, argb, 0, w);
        javax.imageio.ImageIO.write(img, "png", out);
    }
}
