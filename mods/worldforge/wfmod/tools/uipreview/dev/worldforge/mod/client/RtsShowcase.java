package dev.worldforge.mod.client;

import dev.worldforge.settle.*;
import dev.worldforge.rts.mc.client.RtsHud;
import dev.worldforge.rts.*;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

import java.nio.file.*;
import java.util.*;

/** java RtsShowcase <wfaddons dir> <out dir> [w h] */
public class RtsShowcase {
    static void shot(RtsHud h, int W, int H, int mx, int my, String out) throws Exception {
        h.width = W; h.height = H; h.font = new Font();
        GuiGraphics g = new GuiGraphics(W, H);
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x += 8) g.fill(x, y, x + 8, y + 1, ((x / 8 + y / 3) % 7 == 0) ? 0xFF3F6B34 : 0xFF4A7A3C);
        h.render(g, mx, my, 0f);
        javax.imageio.ImageIO.write(g.img, "png", new java.io.File(out));
    }

    static Settlement make(String dir) throws Exception {
        Defs d = new Defs();
        try (var s = Files.list(Path.of(dir, "wf-settlements/src/main/resources/data/wf_settle/wf_settle"))) { for (Path p : (Iterable<Path>) s.sorted()::iterator) d.add(Files.readString(p)); }
        Settlement st = new Settlement("s1", "Белая Гавань", d);
        st.add("wood", 120); st.add("stone", 30); st.add("food", 70);
        for (int i = 0; i < 9; i++) st.addWorker(null);
        st.place("house", 0, 0); st.place("lumber_camp", 14, 0); st.place("farm", 26, 6); st.place("quarry", -22, 12);
        st.tick(160);
        st.place("sawmill", 14, 14); st.place("house", -14, 0);
        st.tick(30);
        return st;
    }

    public static void main(String[] a) throws Exception {
        Settlement st = make(a[0]);
        int W = a.length > 2 ? Integer.parseInt(a[2]) : 1280, H = a.length > 3 ? Integer.parseInt(a[3]) : 720;
        String out = a[1];
        List<String> sent = new ArrayList<>();
        RtsHud h = new RtsHud(st, sent::add);
        shot(h, W, H, W / 2, H / 3, out + "/rts_none.png");
        h.sel.selectWhere(st, w -> w.profession.equals("lumberjack") || w.profession.equals("farmer"), false);
        shot(h, W, H, W - 100, H - 70, out + "/rts_workers.png");
        h.sel.selectBuilding(st.buildings.get(1).uid);
        shot(h, W, H, W - 100, H - 70, out + "/rts_building.png");
        h.sel.clear(); h.sel.selectAll(st);
        h.setMenu("build:housing");
        shot(h, W, H, W - 60, H - 90, out + "/rts_build.png");
        h.setMenu("root"); h.setPrioritiesOpen(true);
        shot(h, W, H, W / 2, H / 2, out + "/rts_prio.png");
        System.exit(0);
    }
}
