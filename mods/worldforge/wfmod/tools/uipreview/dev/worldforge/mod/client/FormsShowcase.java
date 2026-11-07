package dev.worldforge.mod.client;

import dev.worldforge.forms.*;
import dev.worldforge.forms.mc.client.FormsScreen;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

import java.nio.file.*;
import java.util.*;

/** java FormsShowcase <wfaddons> <out> */
public class FormsShowcase {
    public static void main(String[] a) throws Exception {
        FormDefs d = new FormDefs();
        d.load(Files.readString(Path.of(a[0], "wf-forms/src/main/resources/data/wf_forms/wf_forms/forms.json")));
        Forms f = new Forms(d);
        f.hooks = new Forms.Hooks0() { @Override public int level(String o) { return 24; } @Override public boolean isRace(String o, String r) { return r.equals("human"); } };
        f.rng = new Random(3);
        String P = "P";
        f.infect(P, "vampire", 1, "укус"); f.infect(P, "dragon_blood", 1, "родословная"); f.infect(P, "cursed_blood", 2, "гробница");
        f.get(P, "vampire").progress = 30; f.get(P, "vampire").control = 20; f.expose(P, "vampire");
        for (int day = 2; day < 12; day++) f.tick(P, new Forms.Env(day, false, false, day % 3 == 0, false, false, false, false, false, false));
        f.get(P, "cursed_blood").active = true;
        String json = f.viewJson(P);
        for (int i = 0; i < 3; i++) {
            FormsScreen s = new FormsScreen(json, c -> {});
            java.lang.reflect.Field fld = FormsScreen.class.getDeclaredField("sel"); fld.setAccessible(true); fld.setInt(s, i);
            s.width = 1280; s.height = 720; s.font = new Font();
            GuiGraphics g = new GuiGraphics(1280, 720);
            g.fill(0, 0, 1280, 720, WFUi.BG);
            s.render(g, 700, 300, 0f);
            javax.imageio.ImageIO.write(g.img, "png", new java.io.File(a[1] + "/forms_" + i + ".png"));
        }
        System.exit(0);
    }
}
