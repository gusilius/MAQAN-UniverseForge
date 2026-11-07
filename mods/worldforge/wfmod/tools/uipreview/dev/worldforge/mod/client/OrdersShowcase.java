package dev.worldforge.mod.client;

import dev.worldforge.orders.*;
import dev.worldforge.orders.mc.client.OrdersScreen;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

import java.nio.file.*;
import java.util.*;

/** java OrdersShowcase <wfaddons> <out> */
public class OrdersShowcase {
    public static void main(String[] a) throws Exception {
        Orders o = new Orders();
        Map<String, Integer> inv = new HashMap<>(Map.of("gold", 900, "wheat", 64, "iron_ingot", 30));
        o.hooks = new Orders.Hooks0() {
            @Override public int count(String p, String i) { return inv.getOrDefault(i, 0); }
            @Override public boolean take(String p, String i, int n) { if (count(p, i) < n) return false; inv.merge(i, -n, Integer::sum); return true; }
            @Override public void give(String p, String i, int n) { inv.merge(i, n, Integer::sum); }
            @Override public String realmOf(String p) { return "stark"; }
        };
        try (var s = Files.list(Path.of(a[0], "wf-orders/src/main/resources/data/wf_orders/wf_orders"))) { for (Path p : (Iterable<Path>) s.sorted()::iterator) o.load(Files.readString(p)); }
        String P = "p";
        Random rng = new Random(3);
        o.join(P, "merchants_guild", 1); o.join(P, "smiths_guild", 1);
        o.addInfluence(P, "merchants_guild", 70); o.addInfluence(P, "smiths_guild", 20);
        o.offers(P, "merchants_guild", 5, rng); o.offers(P, "smiths_guild", 5, rng);
        for (var c : o.contractsOf(P, "merchants_guild")) if (c.id % 2 == 0) o.accept(P, c.id);
        for (int d = 6; d < 40; d++) o.tick(d, rng);
        o.rivalry(rng);
        o.offers(P, "merchants_guild", 40, rng); o.offers(P, "smiths_guild", 40, rng);
        for (var c : o.contractsOf(P, "merchants_guild")) if (c.type.equals("deed")) { o.accept(P, c.id); o.report(P, "kill_hostile", 5); }
        String json = o.viewJson(P, 40, "stark");
        String[] names = {"overview", "contracts", "petitions", "ranks"};
        for (int t = 0; t < 4; t++) {
            OrdersScreen s = new OrdersScreen(json, c -> {});
            java.lang.reflect.Field f = OrdersScreen.class.getDeclaredField("tab"); f.setAccessible(true); f.setInt(s, t);
            s.width = 1280; s.height = 720; s.font = new Font();
            GuiGraphics g = new GuiGraphics(1280, 720);
            g.fill(0, 0, 1280, 720, WFUi.BG);
            s.render(g, 700, 300, 0f);
            javax.imageio.ImageIO.write(g.img, "png", new java.io.File(a[1] + "/orders_" + names[t] + ".png"));
        }
        System.exit(0);
    }
}
