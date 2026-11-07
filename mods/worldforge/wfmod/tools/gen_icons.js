// Генерирует client/MapIcons.java из значков веб-карты (index.html): node tools/gen_icons.js
const fs = require("fs");
const s = fs.readFileSync("src/main/resources/assets/worldforge/web/index.html", "utf8");
const a = s.indexOf("const PAL = "), b = s.indexOf("function iconCanvas");
fs.writeFileSync("/tmp/ic.js", s.slice(a, b) + ";module.exports={PAL,ICONS}");
const {PAL, ICONS} = require("/tmp/ic.js");
let out = "package dev.worldforge.mod.client;\n\nimport net.minecraft.client.gui.GuiGraphics;\n\nimport java.util.HashMap;\nimport java.util.Map;\n\n/** Пиксельные значки режимов 12x12 (те же, что на веб-карте). Сгенерировано: node tools/gen_icons.js */\npublic final class MapIcons {\n    private static final Map<Character, Integer> PAL = new HashMap<>();\n    private static final Map<String, String[]> ICONS = new HashMap<>();\n    static {\n";
for (const k in PAL) out += `        PAL.put('${k}', 0xFF${PAL[k].slice(1).toUpperCase()});\n`;
for (const k in ICONS) { if (ICONS[k].length !== 12 || ICONS[k].some(r => r.length !== 12)) throw new Error("bad icon " + k + " " + ICONS[k].map(r => r.length)); out += `        ICONS.put("${k}", new String[]{${ICONS[k].map(r => `"${r}"`).join(", ")}});\n`; }
out += `    }

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
`;
fs.writeFileSync("src/main/java/dev/worldforge/mod/client/MapIcons.java", out);
console.log("ok", Object.keys(ICONS).join(","));
