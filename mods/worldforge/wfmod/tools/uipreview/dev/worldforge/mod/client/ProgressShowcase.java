package dev.worldforge.mod.client;

import dev.worldforge.progress.*;
import dev.worldforge.progress.mc.client.ProgressScreen;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

import java.nio.file.*;
import java.util.*;

/** java ProgressShowcase <wfaddons> <out> */
public class ProgressShowcase {
    public static void main(String[] a) throws Exception {
        ProgressDefs d = new ProgressDefs();
        try (var s = Files.list(Path.of(a[0], "wf-progress/src/main/resources/data/wf_progress/wf_progress"))) { for (Path p : (Iterable<Path>) s.sorted()::iterator) d.load(Files.readString(p)); }
        Progress pr = new Progress(d);
        String P = "p:me";
        pr.autoBuild(P, "warrior", 24, 5);
        Profile p = pr.profile(P);
        p.attrFree = 3; p.skillFree = 2; p.passiveFree = 2; p.xp = 1800;
        String defs = pr.defsJson(), prof = pr.viewJson(P);
        String[] names = {"tree", "attrs", "skills", "stats"};
        for (int t = 0; t < 4; t++) {
            ProgressScreen s = new ProgressScreen(defs, prof, c -> {});
            java.lang.reflect.Field f = ProgressScreen.class.getDeclaredField("tab"); f.setAccessible(true); f.setInt(s, t);
            s.width = 1280; s.height = 720; s.font = new Font();
            GuiGraphics g = new GuiGraphics(1280, 720);
            g.fill(0, 0, 1280, 720, WFUi.BG);
            s.render(g, 640, 360, 0f);
            javax.imageio.ImageIO.write(g.img, "png", new java.io.File(a[1] + "/progress_" + names[t] + ".png"));
        }
        // выбор пути
        ProgressScreen s = new ProgressScreen(defs, new Progress(d).viewJson("p:new"), c -> {});
        s.width = 1280; s.height = 720; s.font = new Font();
        GuiGraphics g = new GuiGraphics(1280, 720); g.fill(0, 0, 1280, 720, WFUi.BG); s.render(g, 640, 360, 0f);
        javax.imageio.ImageIO.write(g.img, "png", new java.io.File(a[1] + "/progress_choose.png"));
        System.exit(0);
    }
}
