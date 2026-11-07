package dev.worldforge.mod.client;

import dev.worldforge.mod.*;
import dev.worldforge.war.*;
import dev.worldforge.war.mc.client.WarScreen;
import dev.worldforge.army.*;
import dev.worldforge.army.mc.client.ArmyScreen;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

import java.nio.file.*;
import java.util.*;

/** java WarShowcase <datapack.zip> <wfaddons> <out> */
public class WarShowcase {
    static void shot(net.minecraft.client.gui.screens.Screen s, int W, int H, int mx, int my, String out) throws Exception {
        s.width = W; s.height = H; s.font = new Font();
        GuiGraphics g = new GuiGraphics(W, H);
        g.fill(0, 0, W, H, WFUi.BG);
        s.render(g, mx, my, 0f);
        javax.imageio.ImageIO.write(g.img, "png", new java.io.File(out));
    }

    public static void main(String[] a) throws Exception {
        MapData m = MapData.load("t", name -> {
            try (var zf = new java.util.zip.ZipFile(a[0])) {
                var e = zf.getEntry("data/planets/worldforge_maps/game_of_thrones/" + name);
                return e == null ? null : zf.getInputStream(e).readAllBytes();
            }
        });
        TitleData td = m.titles;
        TitleEditor ed = new TitleEditor(td);
        WarEngine e = new WarEngine(ed);
        e.loadCasusBelli(Files.readString(Path.of(a[1], "wf-war/src/main/resources/data/wf_war/wf_war/base.json")));
        List<String> free = new ArrayList<>();
        for (TitleData.Realm r : td.realmList()) if (r.overlordId == null && !r.titleKeys.isEmpty()) free.add(r.id);
        String A = free.get(0), B = free.get(1), C = free.get(2), D = free.get(3), E = free.get(5);
        ed.setRealmProp(C, "allies", B);
        ed.setOverlord(D, A, "vassal");
        TitleData.Title goal = td.byKey(td.realms.get(B).titleKeys.get(0));
        War w = e.declare(A, B, "conquest", List.of(goal.id));
        e.occupy(w.id, War.Side.ATTACKER, goal.id);
        e.battle(w.id, War.Side.ATTACKER, 2, "Победа у Переправы");
        e.battle(w.id, War.Side.DEFENDER, 1, "Контратака защитников");
        e.tick(40);
        e.offerPeace(w.id, A, true);
        ArmyEngine ae = new ArmyEngine(e);
        ae.ai = false;
        ae.raise(B, "levy", 400); ae.raise(B, "spear", 150); ae.raise(B, "archer", 120); ae.raise(B, "cavalry", 60); ae.hire(B, "siege", 3);
        Army ar0 = ae.armiesOf(B).get(0);
        ae.move(ar0.id, ae.baroniesOf(A).get(3), B);
        ae.tick(4);
        ae.raise(A, "levy", 300);
        ae.gold.put(B, 140.0);
        for (int i = 0; i < 12; i++) { int bb = ae.baroniesOf(B).get(i); for (int k = 0; k < (i % 4) + 1 && ae.eco.level(bb) < 5; k++) { ae.gold.put(B, ae.gold(B) + 60); ae.eco.develop(B, bb); } }
        ae.gold.put(B, 86.0);
        for (var rr : new java.util.ArrayList<>(e.editor.data().realmList())) if (rr.overlordId == null && !rr.id.equals(B) && !rr.id.equals(A) && !rr.titleKeys.isEmpty() && ae.eco.vassals(B).size() < 2) { e.editor.setOverlord(rr.id, B, "vassal"); }
        ae.invalidate(); ae.eco.setTribute(B, 15); ae.eco.setTax(B, 2);
        ClientClock.update("{\"date\":\"14 Мая 300\",\"speed\":\"x2\",\"paused\":false}");
        String[] wn = {"wars", "diplo", "ledger", "log"};
        for (int t = 0; t < 4; t++) {
            WarScreen s = new WarScreen(e, B, c -> {});
            java.lang.reflect.Field f = WarScreen.class.getDeclaredField("tab"); f.setAccessible(true); f.setInt(s, t);
            if (t == 1) { java.lang.reflect.Field r = WarScreen.class.getDeclaredField("selRealm"); r.setAccessible(true); r.setInt(s, 1); }
            shot(s, 1280, 720, 760, 330, a[2] + "/war_" + wn[t] + ".png");
        }
        String[] an = {"armies", "econ", "pacts"};
        for (int t = 0; t < 3; t++) {
            ArmyScreen s = new ArmyScreen(e, ae, B, c -> {});
            java.lang.reflect.Field f = ArmyScreen.class.getDeclaredField("tab"); f.setAccessible(true); f.setInt(s, t);
            shot(s, 1280, 720, 760, 330, a[2] + "/army_" + an[t] + ".png");
        }
        System.exit(0);
    }
}
