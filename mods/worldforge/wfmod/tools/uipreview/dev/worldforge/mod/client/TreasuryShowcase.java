package dev.worldforge.mod.client;

import dev.worldforge.mod.api.TreasuryApi;
import dev.worldforge.settle.*;
import dev.worldforge.settle.mc.client.TreasuryScreen;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

import java.nio.file.*;
import java.util.*;

/** java TreasuryShowcase <wfaddons> <out> */
public class TreasuryShowcase {
    public static void main(String[] a) throws Exception {
        Defs d = new Defs();
        d.add(Files.readString(Path.of(a[0], "wf-settlements/src/main/resources/data/wf_settle/wf_settle/base.json")));
        List<Settlement> cs = new ArrayList<>();
        String[][] n = {{"winter", "Винтерфелл", "9"}, {"white", "Белая Гавань", "6"}, {"moat", "Кейлин", "4"}, {"last", "Последний Очаг", "3"}};
        for (String[] r : n) {
            Settlement s = new Settlement(r[0], r[1], d); s.add("wood", 100); s.add("food", 9000);
            for (int i = 0; i < Integer.parseInt(r[2]); i++) s.addWorker(null);
            s.place("house", 0, 0); s.place("house", 6, 0); s.tick(80); s.tick(200); cs.add(s);
        }
        TreasuryApi.add("north", 400, "Прочее");
        CityTax t = new CityTax();
        t.setShare("north", 30);
        for (int day = 1; day < 4; day++) { for (Settlement s : cs) { s.add("food", 500); s.tick(200); } t.remit(cs, s -> "north", day); }
        TreasuryApi.add("north", 35, "Торговля: пошлины"); TreasuryApi.add("north", 12, "Торговля: налог гильдии"); TreasuryApi.add("north", -40, "Гранты городам"); TreasuryApi.add("north", 25, "События и задания");
        String json = t.viewJson("north", cs, s -> "north"); 
        TreasuryScreen s = new TreasuryScreen(json, c -> {});
        s.width = 1280; s.height = 720; s.font = new Font();
        GuiGraphics g = new GuiGraphics(1280, 720);
        g.fill(0, 0, 1280, 720, WFUi.BG);
        s.render(g, 700, 300, 0f);
        javax.imageio.ImageIO.write(g.img, "png", new java.io.File(a[1] + "/treasury.png"));
        System.exit(0);
    }
}
