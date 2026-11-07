package dev.worldforge.mod.client;

import dev.worldforge.mod.api.StatApi;
import dev.worldforge.origins.*;
import dev.worldforge.origins.mc.client.OriginsScreen;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

import java.nio.file.*;
import java.util.*;

/** java OriginsShowcase <wfaddons> <out> */
public class OriginsShowcase {
    public static void main(String[] a) throws Exception {
        OriginDefs d = new OriginDefs();
        try (var s = Files.list(Path.of(a[0], "wf-origins/src/main/resources/data/wf_origins/wf_origins"))) { for (Path p : (Iterable<Path>) s.sorted()::iterator) d.load(Files.readString(p)); }
        StatApi.registerSheet("t", ow -> { Map<String, Double> m = new LinkedHashMap<>(); m.put("level", 32.0); for (String k : List.of("might", "agility", "vitality", "martial", "intellect", "learning", "spirit", "diplomacy", "stewardship", "intrigue")) m.put(k, 14.0); return m; });
        Origins o = new Origins(d);
        o.hooks = new Origins.Hooks() { @Override public boolean flag(String ow, String f) { return false; } };
        String P = "p:1";
        o.today = 40;
        o.setRace(P, "wood_elf");
        o.chooseClass(P, "ranger"); o.chooseClass(P, "warden");
        o.learn(P, "hunter"); o.work(P, "hunter", 700); o.learn(P, "falconer"); o.learn(P, "herbalist");
        String json = o.viewJson(P);
        String[] names = {"class", "race", "prof", "summary"};
        for (int t = 0; t < 4; t++) {
            OriginsScreen s = new OriginsScreen(json, c -> {});
            java.lang.reflect.Field f = OriginsScreen.class.getDeclaredField("tab"); f.setAccessible(true); f.setInt(s, t);
            if (t == 1) { var g2 = OriginsScreen.class.getDeclaredField("selRace"); g2.setAccessible(true); g2.set(s, "wood_elf"); }
            if (t == 2) { var g2 = OriginsScreen.class.getDeclaredField("selProf"); g2.setAccessible(true); g2.set(s, "falconer"); }
            s.width = 1280; s.height = 720; s.font = new Font();
            GuiGraphics g = new GuiGraphics(1280, 720);
            g.fill(0, 0, 1280, 720, WFUi.BG);
            s.render(g, 700, 300, 0f);
            javax.imageio.ImageIO.write(g.img, "png", new java.io.File(a[1] + "/origins_" + names[t] + ".png"));
        }
        System.exit(0);
    }
}
