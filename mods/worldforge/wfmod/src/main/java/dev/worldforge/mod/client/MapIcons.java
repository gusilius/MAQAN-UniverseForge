package dev.worldforge.mod.client;

import net.minecraft.client.gui.GuiGraphics;

import java.util.HashMap;
import java.util.Map;

/** Пиксельные значки режимов 12x12 (те же, что на веб-карте). Сгенерировано: node tools/gen_icons.js */
public final class MapIcons {
    private static final Map<Character, Integer> PAL = new HashMap<>();
    private static final Map<String, String[]> ICONS = new HashMap<>();
    static {
        PAL.put('k', 0xFF14100C);
        PAL.put('w', 0xFFE8E2D0);
        PAL.put('g', 0xFF8D8D96);
        PAL.put('d', 0xFF55555E);
        PAL.put('G', 0xFF4F9D3A);
        PAL.put('L', 0xFF7ED05A);
        PAL.put('b', 0xFF8A5A2B);
        PAL.put('B', 0xFF5A98FF);
        PAL.put('y', 0xFFF0C85A);
        PAL.put('Y', 0xFFB8902A);
        PAL.put('r', 0xFFC4413A);
        PAL.put('R', 0xFF8F2A26);
        PAL.put('o', 0xFFE08A2E);
        PAL.put('p', 0xFFA35BD8);
        PAL.put('s', 0xFFD9C28A);
        ICONS.put("terrain", new String[]{"............", ".........yy.", "........yyyy", "...w.....yy.", "..wgw.......", "..wggw..w...", ".wggggwwgw..", ".gggggdgggw.", "GLGGGGGGGGGG", "GGGGGGGGGGGG", "bGGGGGGGGGGb", "............"});
        ICONS.put("t0", new String[]{"............", ".....kk.....", "....krrk....", "...krrrrk...", "..krrrrrrk..", ".krrrrrrrrk.", "..kssssssk..", "..kssbbssk..", "..kssbbssk..", "..kkkkkkkk..", "............", "............"});
        ICONS.put("t1", new String[]{"............", "..k.k..k.k..", "..kgkkkkgk..", "..kggggggk..", "...kgggggk..", "...kgkkggk..", "...kgkkggk..", "...kgggggk..", "..kkkkkkkkk.", "............", "............", "............"});
        ICONS.put("t2", new String[]{"............", "..kkkkkkkk..", ".kBBBkRRRRk.", ".kBBBkRRRRk.", ".kBBBkRRRRk.", ".kkkkkkkkkk.", ".kRRRRkBBBk.", "..kRRRkBBk..", "...kRRkBk...", "....kkkk....", "............", "............"});
        ICONS.put("t3", new String[]{"............", "..k..kk..k..", ".kyk.kyk.kyk", ".kyykkyykyyk", ".kyyyyyyyyk.", ".kyyyyyyyyk.", ".kYYYYYYYYk.", ".kkkkkkkkkk.", "............", "............", "............", "............"});
        ICONS.put("t4", new String[]{"....kyyk....", "...kyyyyk...", "..k.kyyk.k..", ".kyk.kk.kyk.", ".kyykyykyyk.", ".kyyyyyyyyk.", ".kyrykkyryk.", ".kyyyyyyyyk.", ".kYYYYYYYYk.", ".kkkkkkkkkk.", "............", "............"});
        ICONS.put("prop", new String[]{"............", "..kkkkkkkk..", ".kBBBBBBBBk.", ".kBBBBBBBBk.", ".kBBByyBBBk.", ".kBBByyBBBk.", ".kBBBBBBBBk.", ".kBBBBBBBBk.", "..kBBBBBBk..", "...kBBBBk...", "....kBBk....", ".....kk....."});
        ICONS.put("dip", new String[]{"............", ".kk......kk.", ".kBBk..kRRk.", ".kBBkyykRRk.", ".kBBBkkRRRk.", "..kBBkRRk...", "...kkyykk...", "....kyyk....", ".....kk.....", "............", "............", "............"});
        ICONS.put("realms", new String[]{"............", ".kk.........", ".krkkkkkkk..", ".krrrrrrrrk.", ".krrBrBrrrk.", ".krrrrrrrrk.", ".krrrrrrrk..", ".kkkkkkkk...", ".kb.........", ".kb.........", ".kb.........", ".kk........."});
    }

    private MapIcons() {}

    public static String keyFor(String mode, java.util.List<String> tiers) {
        if ("terrain".equals(mode) || "realms".equals(mode)) return mode;
        if (mode.startsWith("p:")) return "prop";
        if (mode.startsWith("dip:")) return "dip";
        int i = tiers.indexOf(mode);
        return "t" + Math.min(4, Math.max(0, i));
    }

    /** Рисует значок key в квадрате size x size (size кратен 12 даёт ровные пиксели). */
    public static void draw(GuiGraphics g, String key, int x, int y, int size) {
        String[] rows = ICONS.getOrDefault(key, ICONS.get("t0"));
        for (int j = 0; j < 12; j++) {
            int y0 = y + j * size / 12, y1 = y + (j + 1) * size / 12;
            for (int i = 0; i < 12; i++) {
                Integer c = PAL.get(rows[j].charAt(i));
                if (c == null) continue;
                int x0 = x + i * size / 12, x1 = x + (i + 1) * size / 12;
                g.fill(x0, y0, x1, y1, c);
            }
        }
    }
}
