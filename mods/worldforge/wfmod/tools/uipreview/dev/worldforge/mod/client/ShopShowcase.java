package dev.worldforge.mod.client;

import dev.worldforge.mod.api.DemoApi;
import dev.worldforge.trade.*;
import dev.worldforge.trade.mc.client.ShopScreen;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

import java.nio.file.*;
import java.util.*;

/** java ShopShowcase <wfaddons> <out> */
public class ShopShowcase {
    public static void main(String[] a) throws Exception {
        TradeDefs d = new TradeDefs();
        d.load(Files.readString(Path.of(a[0], "wf-trade/src/main/resources/data/wf_trade/wf_trade/goods.json")));
        DemoApi.registerTerrain("t", r -> Map.of("plains", 0.6, "forest", 0.4));
        Trade t = new Trade(d);
        t.rng = new Random(3);
        for (int day = 1; day < 40; day++) t.tick(day, List.of("north"));
        Shops sh = new Shops(t);
        Shops.Shop s = sh.create("", "Лавка Борина", "north", "overworld", 0, 64, 0, List.of());
        ShopScreen sc = new ShopScreen(sh.viewJson(s.id), c -> {});
        sc.width = 1280; sc.height = 720; sc.font = new Font();
        GuiGraphics g = new GuiGraphics(1280, 720);
        g.fill(0, 0, 1280, 720, WFUi.BG);
        sc.render(g, 700, 300, 0f);
        javax.imageio.ImageIO.write(g.img, "png", new java.io.File(a[1] + "/shop.png"));
        System.exit(0);
    }
}
