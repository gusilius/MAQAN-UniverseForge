package dev.worldforge.mod.client;

import dev.worldforge.mod.api.DemoApi;
import dev.worldforge.trade.*;
import dev.worldforge.trade.mc.client.TradeScreen;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

import java.nio.file.*;
import java.util.*;

/** java TradeShowcase <wfaddons> <out> */
public class TradeShowcase {
    public static void main(String[] a) throws Exception {
        TradeDefs d = new TradeDefs();
        d.load(Files.readString(Path.of(a[0], "wf-trade/src/main/resources/data/wf_trade/wf_trade/goods.json")));
        DemoApi.registerTerrain("t", r -> switch (r) { case "field" -> Map.of("plains", 0.7, "forest", 0.3); case "peak" -> Map.of("mountains", 1.0); default -> Map.of("coast", 1.0); });
        DemoApi.registerRaces("t", r -> switch (r) { case "peak" -> Map.of("dwarf", 0.8, "human", 0.2); case "port" -> Map.of("triton", 0.4, "human", 0.6); default -> Map.of("human", 0.7, "elf", 0.3); });
        Trade t = new Trade(d);
        int[] gold = {800}; double[] danger = {0};
        t.hooks = new Trade.Hooks() {
            @Override public List<String> neighbors(String r) { return r.equals("field") ? List.of("peak", "port") : List.of("field"); }
            @Override public double danger(String a, String b) { return danger[0]; }
            @Override public int gold(String o) { return gold[0]; }
            @Override public boolean takeGold(String o, int n) { gold[0] -= n; return true; }
            @Override public void giveGold(String o, int n) { gold[0] += n; }
        };
        t.rng = new Random(3);
        List<String> realms = List.of("field", "peak", "port");
        for (int day = 1; day < 60; day++) t.tick(day, realms);
        t.dispatch("p:1", "port", "field", "fish", 30, 1, 60);
        danger[0] = 1.0;
        t.dispatch("p:1", "peak", "field", "iron", 25, 0, 60);
        t.tick(61, realms);
        danger[0] = 0;
        String json = t.viewJson("p:1", "field", realms);
        String[] names = {"goods", "caravans", "policy", "log"};
        for (int i = 0; i < 4; i++) {
            TradeScreen s = new TradeScreen(json, c -> {});
            java.lang.reflect.Field f = TradeScreen.class.getDeclaredField("tab"); f.setAccessible(true); f.setInt(s, i);
            s.width = 1280; s.height = 720; s.font = new Font();
            GuiGraphics g = new GuiGraphics(1280, 720);
            g.fill(0, 0, 1280, 720, WFUi.BG);
            s.render(g, 700, 300, 0f);
            javax.imageio.ImageIO.write(g.img, "png", new java.io.File(a[1] + "/trade_" + names[i] + ".png"));
        }
        System.exit(0);
    }
}
